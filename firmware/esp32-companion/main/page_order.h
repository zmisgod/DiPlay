#pragma once
/* Stable wire IDs: NAV=0, MUSIC=1, MAP=2. Only the browsing order changes. */
static inline int companion_next_page(int page) { return page==0?2:page==2?1:0; }
static inline int companion_previous_page(int page) { return page==0?1:page==1?2:0; }
