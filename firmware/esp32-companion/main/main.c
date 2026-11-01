#include "page_order.h"
#include <string.h>
#include <stdlib.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_wifi.h"
#include "esp_http_server.h"
#include "esp_timer.h"
#include "esp_random.h"
#include "esp_log.h"
#include "driver/usb_serial_jtag.h"
#include "esp_heap_caps.h"
#include "src/libs/tjpgd/tjpgd.h"
#include "cJSON.h"
#include "lvgl.h"
#include "src/misc/cache/instance/lv_image_cache.h"
#include "bsp/esp-bsp.h"

#define AP_PASSWORD "12345689"
#define PAIRING_KEY "12345689"
#define MAX_FRAME (128*1024)
static lv_obj_t *picture,*message;
static lv_image_dsc_t image;
static uint8_t *pixels;
static int page;
static uint32_t revision;
static int64_t last_frame;
static char key[17],ssid[33],ip[24]="192.168.77.1",ap_name[32];
static nvs_handle_t storage;
static uint64_t last_command;
static char last_client[65];
static bool wifi_running=false;
static bool usb_only=false;

static void waiting(void) {
    lv_obj_add_flag(picture,LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(message,LV_OBJ_FLAG_HIDDEN);
    char text[300];
    snprintf(text,sizeof(text),"DiPlay ESP32\n%s\n\nWi-Fi: %s\nPassword: " AP_PASSWORD "\nPairing key:\n%s\n\nhttp://%s:8080\nSwipe: NAV / MAP / MUSIC",page==0?"NAVIGATION":page==1?"MUSIC":"CARPLAY",ap_name,key,ip);
    if(usb_only) snprintf(text,sizeof(text),"DiPlay ESP32 USB\n%s\n\nConnect a data cable\nto the head unit.\nSelect USB in DiPlay.\n\nHold screen to enable Wi-Fi",page==0?"NAVIGATION":page==1?"MUSIC":"MAP");
    lv_label_set_text(message,text);
}
static void change_page(int next) { page=next;revision++;last_frame=0;waiting(); }
static void gesture(lv_event_t *e) {
    lv_dir_t dir=lv_indev_get_gesture_dir(lv_indev_active());
    if(dir==LV_DIR_LEFT) change_page(companion_next_page(page));
    else if(dir==LV_DIR_RIGHT) change_page(companion_previous_page(page));
}
static void timeout_tick(lv_timer_t *t) {
    if(last_frame && esp_timer_get_time()-last_frame>6000000) { last_frame=0;waiting(); }
}
static bool authorized(httpd_req_t *r) {
    char supplied[65]={0};
    if(httpd_req_get_hdr_value_str(r,"X-DiPlay-Key",supplied,sizeof(supplied))!=ESP_OK || strcmp(supplied,key)) {
        httpd_resp_send_err(r,HTTPD_401_UNAUTHORIZED,"Pairing key required");return false;
    }
    return true;
}
static void state_json(char *reply,size_t size) {
    bsp_display_lock(-1);
    snprintf(reply,size,"{\"protocol\":2,\"pages\":3,\"page\":%d,\"revision\":%lu,\"width\":466,\"height\":466,\"firmware\":\"0.3.3\",\"transport\":\"%s\"}",page,(unsigned long)revision,usb_only?"usb":"wifi");
    bsp_display_unlock();
}
static esp_err_t state(httpd_req_t *r) {
    if(!authorized(r))return ESP_OK;
    char reply[512];state_json(reply,sizeof(reply));
    httpd_resp_set_type(r,"application/json");return httpd_resp_sendstr(r,reply);
}
static char *body(httpd_req_t *r,int max) {
    if(r->content_len<=0 || r->content_len>max) { httpd_resp_send_err(r,HTTPD_400_BAD_REQUEST,"Invalid length");return NULL; }
    char *b=malloc(r->content_len+1);if(!b){httpd_resp_send_err(r,HTTPD_500_INTERNAL_SERVER_ERROR,"Memory");return NULL;}
    int done=0;while(done<r->content_len) { int n=httpd_req_recv(r,b+done,r->content_len-done);if(n<=0){free(b);return NULL;}done+=n; }
    b[done]=0;return b;
}
static int apply_page(const char *b) {
    cJSON *j=cJSON_Parse(b);
    cJSON *p=cJSON_GetObjectItem(j,"page"),*id=cJSON_GetObjectItem(j,"id"),*client=cJSON_GetObjectItem(j,"client");
    if(!cJSON_IsNumber(p)||p->valuedouble!=p->valueint||p->valueint < -1||p->valueint>2||!cJSON_IsNumber(id)||id->valuedouble<0||!cJSON_IsString(client)||strlen(client->valuestring)>64){cJSON_Delete(j);return 400;}
    bsp_display_lock(-1);
    if(strcmp(last_client,client->valuestring) || last_command!=(uint64_t)id->valuedouble) {
        change_page(p->valueint<0?companion_next_page(page):p->valueint);last_command=(uint64_t)id->valuedouble;strlcpy(last_client,client->valuestring,sizeof(last_client));
    }
    bsp_display_unlock();cJSON_Delete(j);return 200;
}
static esp_err_t set_page(httpd_req_t *r) {
    if(!authorized(r))return ESP_OK;
    char *b=body(r,512);if(!b)return ESP_FAIL;
    int status=apply_page(b);free(b);
    if(status!=200)return httpd_resp_send_err(r,HTTPD_400_BAD_REQUEST,"Invalid page command");
    return state(r);
}
/* Decode each JPEG once before handing RGB565 to LVGL. Its partial 50-row display
 * buffer otherwise reopens/decompresses a RAW JPEG for multiple redraw strips. */
typedef struct {
    const uint8_t *jpeg; size_t length,offset; uint16_t *rgb;
} jpeg_decode_ctx;
static size_t jpeg_input(JDEC *jd,uint8_t *out,size_t requested) {
    jpeg_decode_ctx *ctx=jd->device;
    size_t count=ctx->length-ctx->offset;if(count>requested)count=requested;
    if(out)memcpy(out,ctx->jpeg+ctx->offset,count);
    ctx->offset+=count;return count;
}
static int jpeg_output(JDEC *jd,void *block,JRECT *rect) {
    jpeg_decode_ctx *ctx=jd->device;
    if(rect->right>=466 || rect->bottom>=466 || rect->left>rect->right || rect->top>rect->bottom)return 0;
    /* LVGL 9.5 TJPGD writes B,G,R (despite JD_FORMAT=0 being labelled RGB888).
     * LV_COLOR_FORMAT_RGB565 needs red in bits 15..11; do not swap panel byte order. */
    const uint8_t *src=block;
    for(unsigned y=rect->top;y<=rect->bottom;y++)for(unsigned x=rect->left;x<=rect->right;x++) {
        ctx->rgb[y*466+x]=((uint16_t)(src[2]&0xf8)<<8)|((uint16_t)(src[1]&0xfc)<<3)|(src[0]>>3);src+=3;
    }
    return 1;
}
static uint8_t *decode_frame(const uint8_t *jpeg,size_t length) {
    uint16_t *rgb=heap_caps_malloc(466*466*2,MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT);
    void *work=heap_caps_malloc(4096,MALLOC_CAP_INTERNAL|MALLOC_CAP_8BIT);
    if(!rgb || !work){free(rgb);free(work);return NULL;}
    jpeg_decode_ctx ctx={.jpeg=jpeg,.length=length,.rgb=rgb};JDEC decoder;
    JRESULT result=jd_prepare(&decoder,jpeg_input,work,4096,&ctx);
    if(result==JDR_OK && decoder.width==466 && decoder.height==466)result=jd_decomp(&decoder,jpeg_output,0);
    else result=JDR_FMT1;
    free(work);
    if(result!=JDR_OK){free(rgb);return NULL;}
    return (uint8_t*)rgb;
}
static bool jpeg_dimensions(const uint8_t *b, size_t n) {
    if(n<4 || b[0]!=0xff || b[1]!=0xd8)return false;
    size_t i=2;
    while(i+4<=n) {
        if(b[i++]!=0xff)return false;
        while(i<n && b[i]==0xff)i++;
        if(i>=n)return false;
        unsigned marker=b[i++];
        if(marker==0xda || marker==0xd9)return false;
        if(i+2>n)return false;
        size_t len=((size_t)b[i]<<8)|b[i+1];
        if(len<2 || len>n-i)return false;
        if(marker>=0xc0 && marker<=0xcf && marker!=0xc4 && marker!=0xc8 && marker!=0xcc)
            return marker==0xc0 && len>=8 && b[i+2]==8 && (((unsigned)b[i+3]<<8)|b[i+4])==466 && (((unsigned)b[i+5]<<8)|b[i+6])==466 && (b[i+7]==1 || b[i+7]==3);
        i+=len;
    }
    return false;
}
/* Takes ownership of b, including on failure. Shared by HTTP and USB. */
static int accept_frame(char *b,size_t length,uint32_t requested) {
    if(!jpeg_dimensions((uint8_t*)b,length)){free(b);return 400;}
    uint8_t *decoded=decode_frame((uint8_t*)b,length);free(b);
    if(!decoded)return 400;
    bsp_display_lock(-1);
    if(requested!=revision){bsp_display_unlock();free(decoded);return 409;}
    lv_image_dsc_t next={.header={.magic=LV_IMAGE_HEADER_MAGIC,.cf=LV_COLOR_FORMAT_RGB565,.w=466,.h=466,.stride=466*2},.data_size=466*466*2,.data=decoded};
    lv_image_cache_drop(&image);uint8_t *old=pixels;pixels=decoded;image=next;
    lv_image_set_src(picture,&image);lv_obj_remove_flag(picture,LV_OBJ_FLAG_HIDDEN);lv_obj_add_flag(message,LV_OBJ_FLAG_HIDDEN);
    last_frame=esp_timer_get_time();free(old);bsp_display_unlock();return 200;
}
static esp_err_t frame(httpd_req_t *r) {
    if(!authorized(r))return ESP_OK;
    char query[96],rev[24];
    if(httpd_req_get_url_query_str(r,query,sizeof(query))!=ESP_OK || httpd_query_key_value(query,"revision",rev,sizeof(rev))!=ESP_OK)return httpd_resp_send_err(r,HTTPD_400_BAD_REQUEST,"Missing revision");
    char *end;unsigned long requested=strtoul(rev,&end,10);if(end==rev || *end)return httpd_resp_send_err(r,HTTPD_400_BAD_REQUEST,"Invalid revision");
    char *b=body(r,MAX_FRAME);if(!b)return ESP_FAIL;
    int status=accept_frame(b,r->content_len,requested);
    if(status==409){httpd_resp_set_status(r,"409 Conflict");return httpd_resp_sendstr(r,"Page changed");}
    if(status!=200)return httpd_resp_send_err(r,HTTPD_400_BAD_REQUEST,"466x466 JPEG required");
    return httpd_resp_sendstr(r,"OK");
}
static const char setup_html[]="<!doctype html><meta name=viewport content='width=device-width'><h2>DiPlay ESP32 Wi-Fi</h2><p>Use a 2.4 GHz network shared with the head unit.</p><form id=f><input id=k placeholder='Pairing key' type=password required><br><input id=s placeholder='Wi-Fi SSID' maxlength=32 required><br><input id=p placeholder='Wi-Fi password' type=password maxlength=63><br><button>Save Wi-Fi</button></form><p id=o></p><script>f.onsubmit=async(e)=>{e.preventDefault();try{let r=await fetch('/wifi',{method:'POST',headers:{'X-DiPlay-Key':k.value},body:JSON.stringify({ssid:s.value,password:p.value})});o.textContent=r.ok?'Saved. Read the new IP on the display.':'Failed: check pairing key and Wi-Fi';}catch(e){o.textContent='Connection lost';}}</script>";
static esp_err_t setup(httpd_req_t *r){httpd_resp_set_type(r,"text/html");return httpd_resp_sendstr(r,setup_html);}
static esp_err_t wifi_configure(httpd_req_t *r) {
    if(!authorized(r))return ESP_OK;
    char *b=body(r,512);if(!b)return ESP_FAIL;
    cJSON *j=cJSON_Parse(b);free(b);cJSON *s=cJSON_GetObjectItem(j,"ssid"),*p=cJSON_GetObjectItem(j,"password");
    if(!cJSON_IsString(s)||!strlen(s->valuestring)||strlen(s->valuestring)>32||!cJSON_IsString(p)||strlen(p->valuestring)>63) {cJSON_Delete(j);return httpd_resp_send_err(r,HTTPD_400_BAD_REQUEST,"Invalid Wi-Fi settings");}
    wifi_config_t c={0};memcpy(c.sta.ssid,s->valuestring,strlen(s->valuestring));strlcpy((char*)c.sta.password,p->valuestring,sizeof(c.sta.password));
    strlcpy(ssid,s->valuestring,sizeof(ssid));nvs_set_str(storage,"ssid",s->valuestring);nvs_set_str(storage,"password",p->valuestring);nvs_commit(storage);cJSON_Delete(j);
    esp_wifi_disconnect();esp_wifi_set_config(WIFI_IF_STA,&c);esp_wifi_connect();return httpd_resp_sendstr(r,"Saved");
}
static void wifi_event(void *arg,esp_event_base_t base,int32_t event,void *data) {
    if(base==WIFI_EVENT && event==WIFI_EVENT_STA_DISCONNECTED) {if(!usb_only)esp_wifi_connect();strlcpy(ip,"192.168.77.1",sizeof(ip));}
    if(base==IP_EVENT && event==IP_EVENT_STA_GOT_IP) {ip_event_got_ip_t *e=data;snprintf(ip,sizeof(ip),IPSTR,IP2STR(&e->ip_info.ip));}
    bsp_display_lock(-1);if(!last_frame)waiting();bsp_display_unlock();
}
static uint32_t le32(const uint8_t *b) {return (uint32_t)b[0]|((uint32_t)b[1]<<8)|((uint32_t)b[2]<<16)|((uint32_t)b[3]<<24);}
static void put32(uint8_t *b,uint32_t v){for(int i=0;i<4;i++)b[i]=(v>>(i*8))&255;}
static bool usb_read(void *buffer,size_t size) {
    size_t done=0;int64_t deadline=esp_timer_get_time()+4000000;
    while(done<size && esp_timer_get_time()<deadline){int n=usb_serial_jtag_read_bytes((uint8_t*)buffer+done,size-done,pdMS_TO_TICKS(100));if(n>0)done+=n;}
    return done==size;
}
static void usb_reply(uint32_t seq,int status,const char *body) {
    uint8_t h[16]={'D','P','R','3'};size_t size=strlen(body);put32(h+4,seq);put32(h+8,status);put32(h+12,size);
    usb_serial_jtag_write_bytes(h,sizeof(h),pdMS_TO_TICKS(1000));usb_serial_jtag_write_bytes(body,size,pdMS_TO_TICKS(1000));
}
static void enable_wifi(lv_event_t *e) {
    if(!usb_only)return;
    usb_only=false;nvs_set_u8(storage,"usb_only",0);nvs_commit(storage);
    if(esp_wifi_start()==ESP_OK){wifi_running=true;if(ssid[0])esp_wifi_connect();}
    last_frame=0;waiting();
}
static void usb_server(void *arg) {
    uint32_t magic=0;
    while(true){
        uint8_t ch;
        if(usb_serial_jtag_read_bytes(&ch,1,pdMS_TO_TICKS(100))!=1)continue;
        magic=(magic<<8)|ch;if(magic!=0x44504c33)continue;magic=0;
        uint8_t h[12];if(!usb_read(h,sizeof(h)))continue;
        uint32_t seq=le32(h),hlen=le32(h+4),blen=le32(h+8);
        if(hlen==0 || hlen>1024 || blen>MAX_FRAME){usb_reply(seq,400,"Invalid length");continue;}
        char *header=calloc(1,hlen+1),*payload=calloc(1,blen+1);
        if(!header || !payload){free(header);free(payload);usb_reply(seq,500,"Memory");continue;}
        if(!usb_read(header,hlen)||!usb_read(payload,blen)){free(header);free(payload);continue;}
        cJSON *j=cJSON_Parse(header);free(header);
        cJSON *k=cJSON_GetObjectItem(j,"key"),*p=cJSON_GetObjectItem(j,"path");
        int status=400;char reply[512]="Bad request";
        if(!cJSON_IsString(k)||strcmp(k->valuestring,key)){status=401;strlcpy(reply,"Pairing key required",sizeof(reply));}
        else if(cJSON_IsString(p)){
            // USB mode never joins or changes the head unit's network. Shut our own radio down.
            if(!usb_only){usb_only=true;nvs_set_u8(storage,"usb_only",1);nvs_commit(storage);if(wifi_running){esp_wifi_stop();wifi_running=false;}}
            if(strcmp(p->valuestring,"/state")==0){status=200;state_json(reply,sizeof(reply));}
            else if(strcmp(p->valuestring,"/page")==0){status=blen<=512?apply_page(payload):400;if(status==200)state_json(reply,sizeof(reply));}
            else if(strncmp(p->valuestring,"/frame?revision=",16)==0){
                const char *v=p->valuestring+16;char *end;unsigned long r=strtoul(v,&end,10);
                if(end!=v && !*end){status=accept_frame(payload,blen,r);payload=NULL;strlcpy(reply,status==200?"OK":"Invalid frame or revision",sizeof(reply));}
            }
        }
        free(payload);cJSON_Delete(j);usb_reply(seq,status,reply);
    }
}
void app_main(void) {
    esp_err_t init_result=nvs_flash_init();
    if(init_result==ESP_ERR_NVS_NO_FREE_PAGES || init_result==ESP_ERR_NVS_NEW_VERSION_FOUND){ESP_ERROR_CHECK(nvs_flash_erase());init_result=nvs_flash_init();}
    ESP_ERROR_CHECK(init_result);ESP_ERROR_CHECK(nvs_open("diplay",NVS_READWRITE,&storage));
    // Preserve the existing hotspot name while migrating the saved pairing key.
    size_t len=sizeof(ap_name);
    if(nvs_get_str(storage,"ap_name",ap_name,&len)!=ESP_OK) {
        len=sizeof(key);
        if(nvs_get_str(storage,"key",key,&len)!=ESP_OK) strlcpy(key,PAIRING_KEY,sizeof(key));
        snprintf(ap_name,sizeof(ap_name),"DiPlay-ESP32-%.4s",key);
        ESP_ERROR_CHECK(nvs_set_str(storage,"ap_name",ap_name));
    }
    strlcpy(key,PAIRING_KEY,sizeof(key));
    ESP_ERROR_CHECK(nvs_set_str(storage,"key",key));
    ESP_ERROR_CHECK(nvs_commit(storage));
    uint8_t saved_usb=0;nvs_get_u8(storage,"usb_only",&saved_usb);usb_only=saved_usb==1;
    revision=esp_random();
    assert(bsp_display_start()!=NULL);bsp_display_lock(-1);lv_obj_t *screen=lv_screen_active();lv_obj_set_style_bg_color(screen,lv_color_black(),0);lv_obj_set_style_pad_all(screen,0,0);
    picture=lv_image_create(screen);lv_obj_set_style_pad_all(picture,0,0);lv_obj_set_style_border_width(picture,0,0);lv_obj_center(picture);lv_obj_add_flag(picture,LV_OBJ_FLAG_GESTURE_BUBBLE);
    message=lv_label_create(screen);lv_obj_set_width(message,340);lv_obj_set_style_text_align(message,LV_TEXT_ALIGN_CENTER,0);lv_obj_set_style_text_color(message,lv_color_white(),0);lv_obj_set_style_text_font(message,&lv_font_montserrat_20,0);lv_obj_center(message);
    lv_obj_add_event_cb(screen,gesture,LV_EVENT_GESTURE,NULL);lv_obj_add_event_cb(screen,enable_wifi,LV_EVENT_LONG_PRESSED,NULL);lv_timer_create(timeout_tick,500,NULL);waiting();bsp_display_unlock();
    ESP_ERROR_CHECK(esp_netif_init());ESP_ERROR_CHECK(esp_event_loop_create_default());esp_netif_t *ap_netif=esp_netif_create_default_wifi_ap();esp_netif_create_default_wifi_sta();
    // Keep setup AP away from common CarPlay/Android hotspot subnets.
    esp_netif_ip_info_t ap_ip={0};
    esp_netif_set_ip4_addr(&ap_ip.ip,192,168,77,1);esp_netif_set_ip4_addr(&ap_ip.gw,192,168,77,1);esp_netif_set_ip4_addr(&ap_ip.netmask,255,255,255,0);
    ESP_ERROR_CHECK(esp_netif_dhcps_stop(ap_netif));ESP_ERROR_CHECK(esp_netif_set_ip_info(ap_netif,&ap_ip));ESP_ERROR_CHECK(esp_netif_dhcps_start(ap_netif));
    wifi_init_config_t init=WIFI_INIT_CONFIG_DEFAULT();ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT,WIFI_EVENT_STA_DISCONNECTED,wifi_event,NULL));ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT,IP_EVENT_STA_GOT_IP,wifi_event,NULL));
    wifi_config_t ap={0};strlcpy((char*)ap.ap.ssid,ap_name,sizeof(ap.ap.ssid));strlcpy((char*)ap.ap.password,AP_PASSWORD,sizeof(ap.ap.password));ap.ap.authmode=WIFI_AUTH_WPA2_PSK;ap.ap.max_connection=2;ap.ap.channel=6;
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP,&ap));if(!usb_only){ESP_ERROR_CHECK(esp_wifi_start());wifi_running=true;}
    wifi_config_t sta={0};len=sizeof(ssid);if(nvs_get_str(storage,"ssid",ssid,&len)==ESP_OK){memcpy(sta.sta.ssid,ssid,strlen(ssid));len=sizeof(sta.sta.password);nvs_get_str(storage,"password",(char*)sta.sta.password,&len);esp_wifi_set_config(WIFI_IF_STA,&sta);if(!usb_only)esp_wifi_connect();}
    httpd_config_t config=HTTPD_DEFAULT_CONFIG();config.server_port=8080;config.stack_size=8192;config.recv_wait_timeout=3;config.send_wait_timeout=3;
    httpd_handle_t server;ESP_ERROR_CHECK(httpd_start(&server,&config));
    const httpd_uri_t routes[]={ {.uri="/",.method=HTTP_GET,.handler=setup},{.uri="/wifi",.method=HTTP_POST,.handler=wifi_configure},{.uri="/state",.method=HTTP_GET,.handler=state},{.uri="/page",.method=HTTP_POST,.handler=set_page},{.uri="/frame",.method=HTTP_POST,.handler=frame} };
    for(size_t i=0;i<sizeof(routes)/sizeof(routes[0]);i++)ESP_ERROR_CHECK(httpd_register_uri_handler(server,&routes[i]));
    usb_serial_jtag_driver_config_t usb_config={.tx_buffer_size=4096,.rx_buffer_size=8192};
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&usb_config));
    xTaskCreate(usb_server,"diplay_usb",8192,NULL,4,NULL);
    ESP_LOGI("diplay", "Ready: protocol=2 display=466x466 HTTP=8080. Pairing key is shown only on the display.");
}
