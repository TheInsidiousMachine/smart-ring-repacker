/* SY01 app layer (build 7), stock step counting, sleep and SpO2 removed; linked into the space freed by removing the
 * stock SpO2 algorithm (0x10026404..0x1002831c, single caller, result unused).
 *
 *  - Clicker: while HID mode == 1 ("Clicker" chip), the touch pad is a mouse
 *    button (press = down, release = up) for use with an external pointer such
 *    as eye tracking. Stock knock macros and touch menu are off in this mode.
 *  - Gesture: always-on detector (accel at ~100 Hz, read every 40 ms by our
 *    timer; the stock 500 ms drain stays stopped). Double fist bump then a
 *    finger flick shows the built-in message on the screen, then the screen goes off.
 *    Events 7A 13 [kind 1 = armed, 2 = fired, ntrig, narm].
 *  - Motion recorder: streams the same raw samples to the app (frames 7A 11).
 *  - Live heart: red heart LED flashes per detected beat; BPM on screen; raw
 *    PPG batches streamed as 7A 10.
 *  - Custom BLE commands, group 0x7A (stock never uses it):
 *      7A 02 00 [text...]     show up to 5 characters now
 *      7A 03 00 [on]          live heart mode on/off
 *      7A 04 00 [on] [odr]    motion recorder on/off (odr ignored since build 10: always ~100 Hz;
 *                             status frames 7A 12 every 2 s)
 *      7A 05 00 [on]          gesture detector on/off (default on after boot)
 *      7A 06 00               show the gesture message now (display test)
 *      7A 07 00 [i] [5 ch]..  set message frames i, i+1, ... (5 characters each)
 *      7A 09 00 [mode]        0 clock, 1 mouse, 2 heart, 3 keys (also: 5 taps = mouse on/off, hold 2 s = heart on/off;
 *                             neither shortcut works in keys mode, which the app leaves, or a 2 s disconnect)
 *      7A 0A 00 [speed curve dz flags rc]  tilt mouse: speed 0..100 (0 = clicker only), curve 0..100,
 *                             deadzone degrees, flags 1 invert x, 2 invert y, 4 swap, right-click hold ticks
 *      7A 0B 00               mouse: re-capture the neutral hand angle
 *   events: 7A 14 [mode] on mode change; 7A 15 [kind] touch: 0 tap, 1 press, 2 release (hold),
 *           3 right click, 4 five taps, 5 long hold. Build 16: + [t lo] [t hi] = 20 ms tick of the
 *           edge (free-running, wraps at 65536), so the app can time presses without BLE jitter.
 *      7A 08 00 [n] [tpf] [reps]  use frames 0..n-1, tpf x 20 ms each, played reps
 *                             times (n = 0: built-in message). RAM only: the app
 *                             re-sends it on every connect.
 *
 * RAM (only the removed SpO2 code used these; see FIRMWARE_ANALYSIS.md):
 *   A 0x20004b24..0x20004b64  8 B guard (HR array spill) + 56 B heart state
 *   B 0x20002878..0x20002888  16 B heart state (part 2)
 *   C 0x20006808..0x20006828  timer (0x00..0x19), glue at 0x1a..0x1f
 *   D 0x20006c08..0x20006c68  gesture + recorder buffer (only the removed step
 *                             algorithm referenced 0x20006b6c..0x20006e8c)
 *   E 0x20006c70..0x20006d18  message frames set from the app (same step-only range)
 *   F 0x20006d20..0x20006d80  touch, modes, tilt mouse (same step-only range)
 *   G 0x20006d88..0x20006d98  gesture v2 swing state (same step-only range)
 *   H 0x20006da0..0x20006de0  pointer mouse state (same step-only range)
 */
#include <stdint.h>
#include <stddef.h>

#ifndef HOST_TEST
typedef void (*fn3_t)(int, int, int);
typedef void (*fn1_t)(int);
typedef void (*fn0_t)(void);
#define STOCK_TAP      ((fn3_t)0x1000d80d)   /* accel tap detector (x,y,z)          */
#define STOCK_KEY      ((fn1_t)0x1001c561)   /* touch event dispatcher (ev)         */
#define ACC_READ       ((int (*)(void))0x10008151)   /* read accel FIFO -> raw buffers */
#define ACC_COUNT      (*(volatile uint8_t *)0x200025b6)
#define ACC_X          ((const int16_t *)0x20006598)
#define ACC_Y          ((const int16_t *)0x200065d8)
#define ACC_Z          ((const int16_t *)0x20006618)
#define SEND_FRAME     ((int (*)(int, int, int, const uint8_t *, int))0x10014835) /* AB frame -> app */
#define ACC_WR         ((int (*)(int, int, int))0x10007d7d)            /* accel register write (1, reg, val) */
#define ACC_RD         ((int (*)(int, int, int, uint8_t *))0x10007d4d) /* accel register read (1, reg, len, buf) */
#define DRAIN_STOP     ((fn0_t)0x1000632d)   /* stop the stock 500 ms accel drain timer  */
#define DRAIN_START    ((fn0_t)0x100068ed)   /* (re)start it (16384 ticks = 500 ms)      */
#define STOCK_CMD      ((void (*)(const uint8_t *, int))0x10012231) /* BLE command processor */
#define STOCK_PPG      ((int (*)(void *))0x10016a39)  /* PPG FIFO read -> HR arrays   */
#define STOCK_AWAKE    ((int (*)(void))0x1000e1c9)    /* "may draw" check            */
#define START_MEAS     ((fn1_t)0x1000f831)   /* start measurement mode (8 = HR)      */
#define STOP_MEAS      ((fn1_t)0x1000f79d)
#define TIMER_CREATE   ((int (*)(void *const *, int, void (*)(void)))0x10010e9d)
#define TIMER_START    ((int (*)(void *, int, int))0x10010f2d)
#define TIMER_STOP     ((int (*)(void *))0x10010fc9)
#define GPIO_READ      ((int (*)(int))0x100153fd)
#define MOUSE_REPORT   ((void (*)(int, int, int, int))0x100154a9)
#define SCREEN_WAKE    ((fn0_t)0x1001e035)
#define DRAW_TEXT      ((void (*)(const char *, int))0x10019255)
#define CLOCK_DRAW     ((fn0_t)0x100192dd)   /* stock clock page (page 1), redrawn by the stock 1 Hz UI tick */

#define HID_MODE       (*(volatile uint8_t *)0x2000230f)
#define MEAS_MODE      (*(volatile uint8_t *)0x200022b4)   /* current measurement, 0 = none */
#define PPG_COUNT      (*(volatile uint8_t *)0x20004aa0)
#define PPG_DATA       ((const int32_t *)0x20004aa4)
#define PPG_DATA2      ((const int32_t *)0x20004b64)   /* the stock's second PPG channel */
#define PPG_WEAR       (*(volatile uint8_t *)0x20002487)
#define STOCK_HR       (*(volatile uint16_t *)0x20004a68)  /* stock HR algorithm output (0x100167c0), before its x0.97 */   /* stock wear/contact state (semantics unverified) */
#define UI_IDLE        (*(volatile uint16_t *)0x200024d0)  /* screen idle seconds */
#define UI_SCREEN_ON   (*(volatile uint32_t *)0x200024e0)
#define UI_ICON        (*(volatile uint8_t *)0x200024e9)   /* 'r' heart LED, 'g' step LED */

#define HRT   ((heart_t *)0x20004b2c)
#define HRT2  ((heart2_t *)0x20002878)
#define TMR   ((uint8_t *)0x20006808)
#define G     ((glue_t *)0x20006822)
#define G2    G
#define GS    ((gest_t *)0x20006c08)
#define MS    ((msg_t *)0x20006c70)
#define UIS   ((ui_t *)0x20006d20)
#define GS2   ((gest2_t *)0x20006d88)
#define PTR   ((ptr_t *)0x20006da0)
/* BLE link (static analysis, see NOTES 2026-11-20): the stock code asks the phone for a
 * 12.5 ms connection interval on connect and on every app command (0x1000f214), sets
 * LINK_FAST, and its periodic task counts LINK_CNT up to 20 before asking for 187 ms
 * (0x1000f24c). That slow interval made build 12/13's mouse lag and stutter. */
#define LINK_UP        (*(volatile uint8_t *)0x2000228c)   /* set on connect, cleared on disconnect */
#define LINK_FAST      (*(volatile uint8_t *)0x20002001)   /* struct at 0x20002000 (literals at 0x10010af8, 0x100112f4) */
#define LINK_CNT       (*(volatile uint8_t *)0x20002002)   /* build 14 wrote 0x20000001/2 by mistake (ROM RAM) */
#define REQ_FAST       ((fn0_t)0x1000f215)
#define BLE_FREE       ((int (*)(void))0x4e2b)              /* ROM: free TX buffers (stock drops a notify at <= 2) */
#endif

#define BUILD_NO       16                     /* reported in status 7A 12 byte 14 */
#define CLICK_MODE     1
#define TOUCH_PIN      10
#define TICK_32K       655                    /* 20 ms at 32.768 kHz           */
#define TICK_MS        20
#define TEXT_PAGE      0x1b                   /* page id no stock UI code uses */
#define HR_MODE        8                      /* stock measurement mode: heart rate */
#define REC_MAX        10                     /* samples per recorder frame    */
#define ACC_CTRL1      0x20                   /* accel ODR/axes register       */
#define ACC_FIFO_SRC   0x2f
#define ACC_CTRL1_STOCK 0x47                  /* stock active setting (measured 24.7 Hz) */
#define ACC_CTRL1_FAST  0x67                  /* ODR code 6: measured 99 Hz on this ring */
/* gesture detector (tuned on the 2026-11-19 recordings at 99 Hz; units raw>>2, 2048 per g;
 * thresholds on |a[i] - a[i-1]| squared, so orientation does not matter) */
#define GJ_ACT    671089u     /* 0.40 g: "active" sample                        */
#define GJ_ARMPK  10737418u   /* 1.60 g: a real impact inside the bump window   */
#define GJ_CLUST  4194304u    /* 1.00 g: still part of the bump cluster         */
#define GJ_FLICK  2359296u    /* 0.75 g: the flick (build 13; arming now needs a real swing) */
#define G_WIN     40          /* 400 ms activity window                         */
#define G_ARM_N   15          /* active samples in the window to arm            */
#define G_DMIN    20          /* flick at least 200 ms after the last bump jolt */
#define G_DMAX    100         /* disarm after 1 s without a flick               */
#define G_REFR    149         /* 1.5 s before the next detection                */
#define M_MAX     32          /* message frames (5 characters each)             */
#define M_DEF_TPF 8           /* default: 160 ms per scroll step (build 14)     */
#define M_DEF_REP 1           /* default: scroll once                           */
#define M_DEF_N   12          /* "    HI THERE    " in 5-character windows       */
#define G_MAGIC   0x6e57
#define GF_OFF    1           /* detector disabled by the app                   */
#define GF_OWN    2           /* we own the accel FIFO (stock drain stopped)    */
#define GF_REINIT 4           /* stock re-initialised the accel: set ODR again  */
#define GF_ASLEEP 8           /* stock powered the accel down: don't read       */

typedef struct {
    uint16_t t;           /* ms clock, advanced by our 20 ms tick          */
    uint16_t t_batch;     /* arrival of the previous PPG batch             */
    uint16_t lat;         /* batch interval (flash delay)                  */
    uint16_t t_beat;
    uint16_t ibi[5];
    uint16_t q[2];        /* scheduled flash times                         */
    uint16_t per_q4;      /* sample period of the current batch, ms Q4     */
    int32_t  x1, x2;      /* last two bins (Q4)                            */
    int32_t  p1, p2;      /* last two AC values                            */
    uint8_t  nq, bpm, nibi, flags, bin_n, led;   /* nq: low nibble queue, high nibble strays */
    uint16_t t_syn;       /* last fallback flash (stock HR rate)           */
    uint16_t lt;          /* time of the newest bin in lbits               */
    uint32_t lbits;       /* build 15: LED on/off per 40 ms bin, bit 0 newest (pulse upstroke) */
} heart_t;
typedef struct { int32_t dc, envp, envn, bin_sum; } heart2_t;      /* region B */

/* Our timer struct occupies TMR+0x00..0x19 (0x20006808..0x20006821): create
 * writes its index at +0x16..0x19, start writes +0x00..0x14 (verified by
 * running the stock timer code under emulation, tools/emu). Builds 5-7b put
 * glue at 0x2000681a / 0x20006820 - inside the timer - and the recorder flag
 * was wiped on every timer start. Free: TMR+0x1a..0x1f = 0x20006822..27. */
typedef struct { uint8_t click, rec, seq, div, touch, heart; } glue_t;  /* 6 B */
#define H_ON   1
#define H_DRAW 2
#define HF_ARMED 1
#define HF_POL   2
#define HF_HAVE  4
#define HF_SEND  16      /* a PPG batch is waiting to be streamed to the app */

typedef struct {
    uint16_t magic;
    int16_t  p[3];                 /* previous sample                        */
    uint32_t lo;                   /* activity history, bits 0..31          */
    uint8_t  hi;                   /*                    bits 32..39        */
    uint8_t  cnt;                  /* active samples in the window          */
    uint8_t  pk_age, since;        /* samples since an impact / bump jolt   */
    uint8_t  armed, refr, have, flags, msg, div, nbuf, ntrig, narm, pad;   /* msg: playing */
    uint8_t  play_i, play_t, play_r, pad2;
    int16_t  buf[REC_MAX][3];      /* recorder frame being filled           */
} gest_t;
/* message set from the app (7A 07 / 7A 08); n = 0 -> built-in message */
typedef struct {
    uint8_t n, tpf, reps, pad;     /* frames, 20 ms ticks per frame, plays  */
    char    f[M_MAX][5];
} msg_t;

/* gesture v2 (region G): the bump pair must come with a real arm swing; long jolt runs (shaking) disarm */
typedef struct {
    int32_t sw;                    /* low-passed | |a| - 1 g |, Q4 raw>>2 */
    uint8_t sw_age, armed_t, need_quiet, pad;
} gest2_t;
#define GJ_SWING  31130            /* 0.95 g (Q4): bump pairs 1.2..2.2, flicks/snaps <= 0.65 */
#define G_MAXC    70               /* still jolting 0.7 s after arming = shaking, not a bump pair */
#define G_QUIET   3                /* after that, wait for calm before arming again */

/* touch / modes / tilt mouse (region F) */
typedef struct {
    uint16_t magic;
    uint8_t  mode, pressed;        /* MODE_*; debounced touch            */
    uint16_t press_t, rel_t;       /* ticks pressed / since release      */
    uint8_t  taps, hold_done;      /* taps in this burst; long hold used */
    uint8_t  btn, pend;            /* HID buttons down; press undecided  */
    uint8_t  moved, need_neutral;
    uint8_t  speed, curve, dz, mflags, rc_ticks, show;   /* settings; show = "MOUSE" banner ticks */
    int32_t  g[3];                 /* low-passed accel, Q4 raw>>2        */
    int32_t  e1[3], e2[3];         /* tilt axes, Q14                      */
    int32_t  acc[2];               /* sub-pixel residue, Q8               */
    uint16_t tick;                 /* build 16: free-running 20 ms tick (touch event stamps) */
    uint8_t  down_t;               /* build 16 keys mode: ticks the link has been down */
} ui_t;
#define MODE_CLOCK 0
#define MODE_MOUSE 1
#define MODE_HEART 2
#define MODE_KEYS  3              /* build 16: gaze keyboard. Touch edges only, fast link, no HID, no shortcuts */
#define KEYS_DROP  100            /* leave keys mode after 2 s without a link */
#define UI_MAGIC   0x5549

/* pointer mouse (region H, build 14): cursor = change of hand angle, 1-euro style filter */
typedef struct {
    uint16_t magic;
    uint8_t  have, still;          /* filter primed; samples without real motion */
    uint8_t  fmax, nosend, fast, pad;   /* most free BLE buffers seen; ticks since a send; fast link asked */
    int32_t  pf[2];                /* filtered tilt projections, Q14 sin */
    int32_t  ed, nf, gm;           /* motion estimate, noise floor (Q4); |g| Q4 */
    int16_t  hold[2], out[2];      /* px held while a press is undecided; px waiting for the link */
} ptr_t;
#define P_MAGIC    0x5054
#define P_AMIN     10             /* filter alpha /256 at rest (~0.6 Hz)          */
#define P_AMAX     200
#define P_BETA     4
#define P_V1       29             /* 10 deg/s per sample (Q14): slow gain below   */
#define P_V2       430            /* 150 deg/s: full acceleration above           */
#define MF_INVX    1
#define MF_INVY    2
#define MF_SWAP    4
#define MF_JOY     8              /* build 14: joystick style; default is the pointer */
#define T_DRAG     16             /* px of motion while pressed before a press becomes a drag */
#define T_TAP      15             /* press shorter than 300 ms = tap    */
#define T_BURST    20             /* taps belong together within 400 ms */
#define T_LONG     100            /* 2 s hold = heart mode on/off       */
#define TK_TAP 0
#define TK_HOLD_START 1
#define TK_HOLD_END 2
#define TK_RIGHT 3
#define TK_FIVE 4
#define TK_LONG 5

#ifndef HOST_TEST
_Static_assert(sizeof(heart_t) <= 56, "heart state must fit region A");
_Static_assert(sizeof(heart2_t) <= 16, "heart state must fit region B");
_Static_assert(sizeof(glue_t) == 6, "glue must fit 0x20006822..27");
_Static_assert(sizeof(gest_t) <= 0x60, "gesture state must fit region D");
_Static_assert(sizeof(msg_t) <= 0xa8, "message must fit region E");
_Static_assert(sizeof(ui_t) <= 0x60, "ui state must fit region F");
_Static_assert(sizeof(gest2_t) <= 0x10, "gesture v2 state must fit region G");
_Static_assert(sizeof(ptr_t) <= 0x40, "pointer state must fit region H");
#endif

static void *const tmr_holder = (void *)TMR;

#ifndef HOST_TEST
void *memset(void *d, int c, size_t n) { uint8_t *p = d; while (n--) *p++ = (uint8_t)c; return d; }
void *memcpy(void *d, const void *s, size_t n) { uint8_t *p = d; const uint8_t *q = s; while (n--) *p++ = *q++; return d; }
#endif

void app_tick(void);
static void timer_need(void)
{
    if (!TMR[0x15]) TIMER_CREATE(&tmr_holder, 1, app_tick);
    if (!TMR[0x14]) TIMER_START(TMR, TICK_32K, 0);
}
static void timer_maybe_stop(void) { }           /* build 12+: touch is ours, the tick always runs */

/* ===================================================================== recorder */

/* 7A 11 payload: seq n (x y z int16 LE) * n, values raw >> 2 (~2048 per g).
 * Sent with the stock frame sender (same path as the stock app events). */
static void rec_send(const int16_t (*v)[3], int n)
{
    uint8_t b[2 + REC_MAX * 6];
    b[0] = G->seq++; b[1] = (uint8_t)n;
    for (int i = 0; i < n; i++)
        for (int k = 0; k < 3; k++) { b[2 + 6 * i + 2 * k] = (uint8_t)v[i][k]; b[3 + 6 * i + 2 * k] = (uint8_t)(v[i][k] >> 8); }
    SEND_FRAME(1, 0x7A11, 0, b, 2 + 6 * n);
}

/* 7A 12 status: rec, timer running, samples in last read, HID mode, seq,
 * accel CTRL1 (ODR) read back from the chip, FIFO_SRC (bit 6 = overrun),
 * gesture flags, triggers, arms */
static void rec_status(int last_n)
{
    uint8_t r[2] = { 0, 0 };
    ACC_RD(1, ACC_CTRL1, 1, &r[0]);
    ACC_RD(1, ACC_FIFO_SRC, 1, &r[1]);
    gest_t *s = GS;
    const msg_t *m = MS;
    uint8_t b[15] = { G->rec, TMR[0x14], (uint8_t)last_n, HID_MODE, G->seq, r[0], r[1], s->flags, s->ntrig, s->narm,
                      m->n, m->tpf, m->reps, UIS->mode, BUILD_NO };
    SEND_FRAME(1, 0x7A12, 0, b, 15);
}

/* ===================================================================== gesture */

static void draw(const char s[6]);
static uint32_t isqrt(uint32_t v);

static void gest_init(void)
{
    gest_t *s = GS;
    if (s->magic == G_MAGIC) return;
    memset(s, 0, sizeof(gest_t));
    memset(MS, 0, sizeof(msg_t));
    memset(GS2, 0, sizeof(gest2_t));
    GS2->sw_age = 255;
    s->magic = G_MAGIC;
    s->pk_age = s->since = 255;
}

static int gest_want(void) { return !(GS->flags & GF_OFF) || G->rec || UIS->mode == MODE_MOUSE; }
static void mouse_sample(ui_t *u, int x, int y, int z);

static void gest_reset(gest_t *s)
{
    s->have = 0; s->lo = 0; s->hi = 0; s->cnt = 0; s->armed = 0; s->refr = 0;
    s->pk_age = s->since = 255;
    GS2->sw = 0; GS2->sw_age = 255; GS2->armed_t = 0; GS2->need_quiet = 0;
}

static void gest_event(int kind)
{
    uint8_t b[3] = { (uint8_t)kind, GS->ntrig, GS->narm };
    SEND_FRAME(1, 0x7A13, 0, b, 3);
}

static const char m_def[] = "    HI THERE    ";   /* built-in: scrolls once */

static void msg_show(int i)
{
    const msg_t *m = MS;
    const char *src = m->n ? m->f[i] : m_def + i;
    char t[6];
    for (int k = 0; k < 5; k++) t[k] = src[k];
    t[5] = 0;
    draw(t);
}

static void gest_fire(void)
{
    gest_t *s = GS; const msg_t *m = MS;
    s->msg = 1;
    s->play_i = 0;
    s->play_t = m->n ? m->tpf : M_DEF_TPF;
    s->play_r = m->n ? m->reps : M_DEF_REP;
    UI_IDLE = 0;
    SCREEN_WAKE();
    msg_show(0);
}

/* one accel sample; mirrors the offline model (sm.py) sample for sample */
static void gest_sample(gest_t *s, int x, int y, int z)
{
    if (!s->have) { s->p[0] = (int16_t)x; s->p[1] = (int16_t)y; s->p[2] = (int16_t)z; s->have = 1; return; }
    int32_t dx = x - s->p[0], dy = y - s->p[1], dz = z - s->p[2];
    s->p[0] = (int16_t)x; s->p[1] = (int16_t)y; s->p[2] = (int16_t)z;
    uint32_t j = (uint32_t)(dx * dx) + (uint32_t)(dy * dy) + (uint32_t)(dz * dz);
    uint8_t act = j > GJ_ACT, out = (uint8_t)(s->hi >> 7);
    s->hi = (uint8_t)((s->hi << 1) | (s->lo >> 31));
    s->lo = (s->lo << 1) | act;
    s->cnt = (uint8_t)(s->cnt + act - out);
    gest2_t *v = GS2;
    int32_t dm = (int32_t)isqrt((uint32_t)(x * x + y * y + z * z)) - 2048;
    if (dm < 0) dm = -dm;
    v->sw += (((dm << 4) - v->sw) * 5) >> 5;       /* ~0.15 per sample (~60 ms) */
    if (j >= GJ_ARMPK) s->pk_age = 0; else if (s->pk_age < 255) s->pk_age++;
    if (v->sw >= GJ_SWING) v->sw_age = 0; else if (v->sw_age < 255) v->sw_age++;
    if (s->since < 255) s->since++;
    if (s->refr) { s->refr--; return; }
    if (v->need_quiet) { if (s->cnt <= G_QUIET) v->need_quiet = 0; return; }
    if (!s->armed) {
        if (s->cnt >= G_ARM_N && s->pk_age < G_WIN && v->sw_age < G_WIN) {
            s->armed = 1; s->since = 0; s->narm++; v->armed_t = 0;
            gest_event(1);
        }
        return;
    }
    if (v->armed_t < 255) v->armed_t++;
    if (j >= GJ_FLICK && s->since >= G_DMIN) {
        s->armed = 0; s->refr = G_REFR; s->ntrig++;
        gest_fire();
        gest_event(2);
        return;
    }
    if (j >= GJ_CLUST) {
        s->since = 0;
        if (v->armed_t > G_MAXC) { s->armed = 0; v->need_quiet = 1; }
    } else if (s->since > G_DMAX) s->armed = 0;
}

static void rec_push(gest_t *s, int x, int y, int z)
{
    s->buf[s->nbuf][0] = (int16_t)x; s->buf[s->nbuf][1] = (int16_t)y; s->buf[s->nbuf][2] = (int16_t)z;
    if (++s->nbuf >= REC_MAX) { rec_send((const int16_t (*)[3])s->buf, REC_MAX); s->nbuf = 0; }
}

static void msg_tick(gest_t *s)
{
    if (!s->msg) return;
    UI_IDLE = 0;
    if (s->play_t > 1) { s->play_t--; return; }
    const msg_t *m = MS;
    int n = m->n ? m->n : M_DEF_N;
    if (++s->play_i >= n) {
        s->play_i = 0;
        if (s->play_r <= 1) {                      /* done */
            s->msg = 0;
            if (!(G2->heart & H_ON)) UI_IDLE = 600; /* stock 1 Hz tick turns the screen off */
            return;
        }
        s->play_r--;
    }
    s->play_t = m->n ? m->tpf : M_DEF_TPF;
    msg_show(s->play_i);
}

/* accel owner: FIFO read every 40 ms (~4 samples at 99 Hz) for the detector and the recorder */
static void gest_tick(void)
{
    gest_t *s = GS; glue_t *g = G;
    gest_init();
    msg_tick(s);
    int want = gest_want();
    if (want && (!(s->flags & GF_OWN) || (s->flags & GF_REINIT))) {
        DRAIN_STOP();
        ACC_WR(1, ACC_CTRL1, ACC_CTRL1_FAST);
        ACC_READ();                                /* start from an empty FIFO */
        gest_reset(s);
        s->flags = (uint8_t)((s->flags | GF_OWN) & ~(GF_REINIT | GF_ASLEEP));
        s->div = 0;
    } else if (!want && (s->flags & GF_OWN)) {
        ACC_WR(1, ACC_CTRL1, ACC_CTRL1_STOCK);
        ACC_READ();                                /* drop fast samples before stock resumes */
        DRAIN_START();
        s->flags &= (uint8_t)~GF_OWN;
        gest_reset(s);
        timer_maybe_stop();
        return;
    }
    if (!want) return;
    if (++s->div >= 100) s->div = 0;
    if (s->div == 0) {                             /* every 2 s: is the accel still ours? */
        uint8_t r = 0xff;                          /* stock read returns 1 even on I2C failure: */
        ACC_RD(1, ACC_CTRL1, 1, &r);               /* an untouched 0xff means "no answer"       */
        if (r != 0xff) {
            if (r == 0) s->flags |= GF_ASLEEP;     /* stock powered it down (e.g. off state) */
            else if (r != ACC_CTRL1_FAST) { s->flags |= GF_REINIT; s->flags &= (uint8_t)~GF_ASLEEP; }
            else s->flags &= (uint8_t)~GF_ASLEEP;
        }
    }
    if (((s->div & 1) && UIS->mode != MODE_MOUSE) || (s->flags & GF_ASLEEP)) return;   /* mouse: every 20 ms */
    ACC_READ();
    int n = ACC_COUNT;
    if (n > 32) n = 32;
    int det = !(s->flags & GF_OFF);
    for (int i = 0; i < n; i++) {
        int x = ACC_X[i] >> 2, y = ACC_Y[i] >> 2, z = ACC_Z[i] >> 2;
        if (det) gest_sample(s, x, y, z);
        mouse_sample(UIS, x, y, z);
        if (g->rec) rec_push(s, x, y, z);
    }
    if (g->rec && s->div == 0) rec_status(n);      /* every 2 s while recording */
}

/* ===================================================================== heart */

static void draw(const char s[6])
{
    G2->heart |= H_DRAW;
    DRAW_TEXT(s, TEXT_PAGE);
    G2->heart &= (uint8_t)~H_DRAW;
}

/* the stock algorithm's rate, used while our beat detector has no lock */
static int stock_bpm(void) { unsigned v = STOCK_HR; return (v >= 40 && v <= 180) ? (int)(v * 97 / 100) : 0; }

static void heart_show(heart_t *h, int led)
{
    if (GS->msg) return;                           /* gesture message owns the screen */
    char s[6] = { 'P', 'U', 'L', 'S', 'E', 0 };    /* until a rate is known */
    int b = h->bpm ? h->bpm : stock_bpm();
    if (b) {
        s[3] = s[4] = ' ';
        s[0] = b >= 100 ? (char)('0' + b / 100) : ' ';
        s[1] = (char)('0' + (b / 10) % 10);
        s[2] = (char)('0' + b % 10);
    }
    s[5] = led ? 'r' : 0;
    draw(s);
}

/* typical beat gap: mean of the last n gaps without the highest and lowest */
static uint16_t ibi_center(const heart_t *h)
{
    int n = h->nibi;
    if (n == 0) return 0;
    uint32_t sum = 0; uint16_t lo = 0xffff, hi = 0;
    for (int i = 0; i < n; i++) {
        uint16_t v = h->ibi[i];
        sum += v; if (v < lo) lo = v; if (v > hi) hi = v;
    }
    if (n >= 3) return (uint16_t)((sum - lo - hi) / (uint32_t)(n - 2));
    return (uint16_t)(sum / (uint32_t)n);
}

static void heart_beat(heart_t *h, uint16_t ts)
{
    uint16_t ibi = (uint16_t)(ts - h->t_beat);
    h->t_beat = ts;
    h->flags &= (uint8_t)~HF_ARMED;
    if (ibi < 300 || ibi > 2000) return;           /* 30..200 bpm only */
    if (h->nibi >= 3) {
        uint16_t m = ibi_center(h);
        uint16_t lo = (uint16_t)(m - m / 3), hi = (uint16_t)(m + m / 3);
        if (ibi < lo || ibi > hi) {                /* stray beat: no flash, no update */
            if ((h->nq >> 4) < 3) { h->nq = (uint8_t)(h->nq + 16); return; }
            h->nibi = 0;                           /* 4 strays in a row: rate really changed */
        }
    }
    h->nq &= 0x0f;
    for (int i = 4; i > 0; i--) h->ibi[i] = h->ibi[i - 1];
    h->ibi[0] = ibi;
    if (h->nibi < 5) h->nibi++;
    if (h->nibi >= 2) {
        uint16_t m = ibi_center(h);
        h->bpm = (uint8_t)((60000u + m / 2) / m);
    }
    if ((h->nq & 0x0f) < 2) { h->q[h->nq & 0x0f] = (uint16_t)(ts + h->lat + 40); h->nq++; }
}

static int32_t med3(int32_t a, int32_t b, int32_t c)
{
    if (a > b) { int32_t t = a; a = b; b = t; }
    if (b > c) b = c;
    return a > b ? a : b;
}

static void heart_bin(heart_t *h, int32_t x, uint16_t ts)
{
    heart2_t *k = HRT2;
    int32_t v = x << 4;
    int32_t m = med3(h->x2, h->x1, v);             /* drops single-bin spikes */
    h->x2 = h->x1; h->x1 = v;
    k->dc += (m - k->dc) >> 5;                     /* baseline (high-pass)    */
    int32_t ac = (m - k->dc) >> 4;
    int32_t d = ac - h->p2;                        /* slope over 2 bins (~80 ms) */
    h->p2 = h->p1; h->p1 = ac;
    k->envp -= k->envp >> 6;
    k->envn -= k->envn >> 6;
    /* once locked on a rhythm, an envelope may grow at most 1.5x per bin, so
     * one motion artefact cannot blind the detector for seconds */
    int32_t cp = h->nibi >= 2 ? k->envp + (k->envp >> 1) + 1 : 0x7fffffff;
    int32_t cn = h->nibi >= 2 ? k->envn + (k->envn >> 1) + 1 : 0x7fffffff;
    if (d > k->envp) k->envp = d < cp ? d : cp;
    if (-d > k->envn) k->envn = -d < cn ? -d : cn;
    /* beats are the steep edge of the pulse; pick whichever polarity is steeper */
    int pol = (h->flags & HF_POL) != 0;
    if (!pol && k->envp * 2 > k->envn * 3) h->flags |= HF_POL, pol = 1;
    else if (pol && k->envn * 2 > k->envp * 3) h->flags &= (uint8_t)~HF_POL, pol = 0;
    int32_t q = pol ? d : -d, env = pol ? k->envp : k->envn;
    h->lbits = (h->lbits << 1) | (uint32_t)(env > 0 && q * 2 > env);   /* LED follows the pulse edge */
    h->lt = ts;
    if (h->flags & HF_ARMED) {
        uint16_t refr = 300;
        if (h->nibi >= 3) {                        /* locked: no second trigger inside a beat */
            uint16_t m = ibi_center(h);
            refr = (uint16_t)(m * 3u / 5);
            if (refr < 300) refr = 300;
        }
        if (env > 0 && q * 2 > env && (uint16_t)(ts - h->t_beat) >= refr) heart_beat(h, ts);
    } else if (q * 5 < env) {
        h->flags |= HF_ARMED;
    }
}

/* average raw samples into ~40 ms bins so the detector behaves the same at
 * any sensor rate */
static void heart_sample(heart_t *h, int32_t x, uint16_t ts)
{
    heart2_t *k = HRT2;
    uint32_t m = h->per_q4 ? ((40u << 4) + h->per_q4 / 2) / h->per_q4 : 1;
    if (m < 1) m = 1;
    if (m > 8) m = 8;
    k->bin_sum += x;
    if (++h->bin_n >= m) {
        heart_bin(h, k->bin_sum / (int32_t)h->bin_n, ts);
        k->bin_sum = 0; h->bin_n = 0;
    }
}

static void heart_batch(heart_t *h, const int32_t *x, int n)
{
    uint16_t now = h->t;
    if (!(h->flags & HF_HAVE)) {
        if (n) { HRT2->dc = h->x1 = h->x2 = x[n - 1] << 4; h->p1 = h->p2 = 0; h->flags |= HF_HAVE; }
        h->t_batch = now;
        h->t_beat = (uint16_t)(now - 2000);
        return;
    }
    uint16_t dt = (uint16_t)(now - h->t_batch);
    h->t_batch = now;
    if (n == 0 || dt == 0 || dt > 4000) return;
    uint16_t per = (uint16_t)(((uint32_t)dt << 4) / (uint32_t)n);
    h->per_q4 = per;
    h->lat = h->lat ? (uint16_t)((h->lat * 3u + dt) / 4) : dt;
    for (int i = 0; i < n; i++) {
        uint16_t ts = (uint16_t)(now - (((uint32_t)(n - 1 - i) * h->per_q4) >> 4));
        heart_sample(h, x[i], ts);
    }
}

/* stream the latest PPG batch to the app: 7A 10, payload
 * [n, flags, bpm, nibi, lat lo, lat hi, per_q4 lo, per_q4 hi, n x int24 LE] */
static void heart_stream(heart_t *h)
{
    uint8_t b[8 + 34 * 3];
    int n = PPG_COUNT;
    if (n > 34) n = 34;
    b[0] = (uint8_t)n; b[1] = h->flags; b[2] = h->bpm; b[3] = h->nibi;
    b[4] = (uint8_t)h->lat; b[5] = (uint8_t)(h->lat >> 8);
    b[6] = (uint8_t)h->per_q4; b[7] = (uint8_t)(h->per_q4 >> 8);
    for (int i = 0; i < n; i++) {
        int32_t v = PPG_DATA[i];
        b[8 + 3 * i] = (uint8_t)v; b[9 + 3 * i] = (uint8_t)(v >> 8); b[10 + 3 * i] = (uint8_t)(v >> 16);
    }
    SEND_FRAME(1, 0x7A10, 0, b, 8 + 3 * n);
}

/* build 14 diagnostics, 7A 16: both PPG channels of the latest batch, so one live capture
 * shows the real sample rate, amplitude and which channel carries the pulse.
 * payload [first, n, wear, meas, t lo, t hi, m x (ch1 int24, ch2 int24)], m <= 16 per frame */
static void heart_diag(heart_t *h)
{
    uint8_t b[6 + 16 * 6];
    int n = PPG_COUNT;
    if (n > 34) n = 34;
    for (int k = 0; k < n || k == 0; k += 16) {
        int m = n - k > 16 ? 16 : n - k;
        b[0] = (uint8_t)k; b[1] = (uint8_t)n; b[2] = PPG_WEAR; b[3] = MEAS_MODE;
        b[4] = (uint8_t)h->t; b[5] = (uint8_t)(h->t >> 8);
        for (int i = 0; i < m; i++) {
            int32_t v = PPG_DATA[k + i], w = PPG_DATA2[k + i];
            uint8_t *o = b + 6 + 6 * i;
            o[0] = (uint8_t)v; o[1] = (uint8_t)(v >> 8); o[2] = (uint8_t)(v >> 16);
            o[3] = (uint8_t)w; o[4] = (uint8_t)(w >> 8); o[5] = (uint8_t)(w >> 16);
        }
        SEND_FRAME(1, 0x7A16, 0, b, 6 + 6 * (m > 0 ? m : 0));
        if (n == 0) break;
    }
}

static void heart_tick(void)
{
    heart_t *h = HRT;
    h->t += TICK_MS;
    if (h->flags & HF_SEND) { h->flags &= (uint8_t)~HF_SEND; heart_stream(h); heart_diag(h); }
    UI_IDLE = 3;                                   /* keep the screen awake   */
    if (!UI_SCREEN_ON) { SCREEN_WAKE(); heart_show(h, 0); }
    /* LED = the recorded pulse edges, replayed one batch late so it runs at the real pace;
     * no signal for 2 s -> blink at the stock algorithm's rate instead */
    int led = 0, live = 0;
    {
        int32_t age = (int16_t)(h->lt - (uint16_t)(h->t - h->lat - 40));
        if (h->lat && age >= 0 && age < 32 * 40) led = (int)((h->lbits >> (age / 40)) & 1);
        live = h->lat && (h->lbits & 0xffffu) && age >= -2000;
    }
    if (!live && stock_bpm()) {
        if ((uint16_t)(h->t - h->t_syn) >= 60000u / (unsigned)stock_bpm()) { h->t_syn = h->t; h->led = 6; }
        if (h->led) { h->led--; led = 1; }
    }
    if (led != (UI_ICON == 'r')) heart_show(h, led);
    if (h->t % 3000 < TICK_MS && MEAS_MODE == 0) START_MEAS(HR_MODE);   /* keep the sensor running */
}

static void heart_set(int on)
{
    if (on) {
        { memset(HRT, 0, sizeof(heart_t)); memset(HRT2, 0, sizeof(heart2_t)); }
        STOCK_HR = 0;                              /* no stale rate from an earlier measurement */
        G2->heart = H_ON;
        timer_need();
        SCREEN_WAKE();
        heart_show(HRT, 0);
        START_MEAS(HR_MODE);
    } else if (G2->heart & H_ON) {
        G2->heart = 0;
        UI_ICON = 0;
        STOP_MEAS(HR_MODE);
        { memset(HRT, 0, sizeof(heart_t)); memset(HRT2, 0, sizeof(heart2_t)); }
        timer_maybe_stop();
    }
}

/* ===================================================================== touch, modes, mouse */

static void ui_init(void)
{
    ui_t *u = UIS;
    if (u->magic == UI_MAGIC) return;
    memset(u, 0, sizeof(ui_t));
    u->magic = UI_MAGIC;
    u->speed = 50; u->curve = 50; u->dz = 4; u->rc_ticks = 35;   /* right click: hold still 700 ms */
    u->rel_t = 0xffff;
}

static void ui_event(int id, int v)
{
    uint8_t b[1] = { (uint8_t)v };
    SEND_FRAME(1, id, 0, b, 1);
}

/* 7A 15 touch event + the 20 ms tick it happened on (build 16; old apps read byte 0 only) */
static void touch_event(int kind)
{
    uint16_t t = UIS->tick;
    uint8_t b[3] = { (uint8_t)kind, (uint8_t)t, (uint8_t)(t >> 8) };
    SEND_FRAME(1, 0x7A15, 0, b, 3);
}

static uint32_t isqrt(uint32_t v)
{
    uint32_t r = 0, bit = 1u << 30;
    while (bit > v) bit >>= 2;
    while (bit) {
        if (v >= r + bit) { v -= r + bit; r = (r >> 1) + bit; } else r >>= 1;
        bit >>= 2;
    }
    return r;
}

static void mouse_recenter(ui_t *u);
static ptr_t *ptr_reset(void)
{
    ptr_t *p = PTR;
    memset(p, 0, sizeof(ptr_t));
    p->magic = P_MAGIC;
    return p;
}
static ptr_t *ptr(void) { return PTR->magic == P_MAGIC ? PTR : ptr_reset(); }

/* pointer: tilt change -> cursor; mirrors model/pointer.py sample for sample */
static void ptr_sample(ui_t *u, int x, int y, int z)
{
    ptr_t *p = ptr();
    uint32_t mag = isqrt((uint32_t)(x * x + y * y + z * z));
    if (mag < 1024) return;
    p->gm = p->gm ? p->gm + (((int32_t)(mag << 4) - p->gm) >> 6) : (int32_t)(mag << 4);
    int32_t q[2], d[2];
    for (int i = 0; i < 2; i++) {
        const int32_t *e = i ? u->e2 : u->e1;
        q[i] = (x * e[0] + y * e[1] + z * e[2]) / (int32_t)mag;
    }
    if (!p->have) { p->pf[0] = q[0]; p->pf[1] = q[1]; p->have = 1; p->ed = 0; if (!p->nf) p->nf = 160 << 4; return; }
    int32_t e0 = q[0] - p->pf[0], e1 = q[1] - p->pf[1];
    int32_t em = e0 < 0 ? -e0 : e0, t1 = e1 < 0 ? -e1 : e1;
    if (t1 > em) em = t1;
    p->ed += ((em << 4) - p->ed) >> 3;
    if (p->ed < p->nf * 2) p->nf += (p->ed - p->nf) >> 7;          /* noise floor follows the still hand */
    int32_t fl = (p->nf >> 4) * 5 / 4 + 40, a = (p->ed >> 4) - fl;
    p->still = a > 0 ? 0 : (p->still < 255 ? p->still + 1 : 255);
    a = a > 0 ? P_AMIN + ((a * P_BETA) >> 4) : P_AMIN;
    if (a > P_AMAX) a = P_AMAX;
    d[0] = e0 * a / 256; d[1] = e1 * a / 256;
    p->pf[0] += d[0]; p->pf[1] += d[1];
    int32_t lin = (int32_t)mag - (p->gm >> 4);
    if (lin < 0) lin = -lin;
    if (lin > 512) d[0] = d[1] = 0;                                 /* hand is accelerating, not tilting */
    else if (lin > 256) { d[0] = d[0] * (512 - lin) / 256; d[1] = d[1] * (512 - lin) / 256; }
    int32_t v = (d[0] < 0 ? -d[0] : d[0]) + (d[1] < 0 ? -d[1] : d[1]);
    int32_t t = v <= P_V1 ? 0 : (v >= P_V2 ? 256 : (v - P_V1) * 256 / (P_V2 - P_V1));
    int32_t glo = u->speed * 1024 / 100, ghi = glo * (100 + u->curve * 6) / 100;    /* px/deg Q8: 50/50 = 2..8 */
    int32_t g = glo + (ghi - glo) * t / 256;
    u->acc[0] += d[0] * g / 286;                                    /* 286 Q14 units per degree */
    u->acc[1] += d[1] * g / 286;
    if (p->still == 60) { mouse_recenter(u); p->have = 0; }         /* re-level the axes while resting */
}

/* low-passed gravity, ~80 ms at 99 Hz */
static void mouse_sample(ui_t *u, int x, int y, int z)
{
    int a[3] = { x, y, z };
    for (int k = 0; k < 3; k++) u->g[k] += ((a[k] << 4) - u->g[k]) >> 3;
    if (u->need_neutral && u->need_neutral < 13) u->need_neutral++;   /* let the filter settle first */
    else if (!u->need_neutral && u->mode == MODE_MOUSE && u->speed && !(u->mflags & MF_JOY)) ptr_sample(u, x, y, z);
}
/* neutral = current hand angle; tilt axes are the two directions perpendicular to it */
static void mouse_recenter(ui_t *u)
{
    int32_t n[3], e3[3];
    for (int k = 0; k < 3; k++) n[k] = u->g[k] >> 4;
    uint32_t m = isqrt((uint32_t)(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]));
    if (m < 512) return;                              /* no sensible gravity yet */
    for (int k = 0; k < 3; k++) e3[k] = n[k] * 16384 / (int32_t)m;
    /* reference: ring x axis, or y if x is almost vertical */
    int ax = (e3[0] > 14000 || e3[0] < -14000) ? 1 : 0;
    int32_t d = e3[ax], e1[3];
    for (int k = 0; k < 3; k++) e1[k] = ((k == ax) ? 16384 : 0) - d * e3[k] / 16384;
    uint32_t l = isqrt((uint32_t)(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2]));
    if (l == 0) return;
    for (int k = 0; k < 3; k++) u->e1[k] = e1[k] * 16384 / (int32_t)l;
    u->e2[0] = (e3[1] * u->e1[2] - e3[2] * u->e1[1]) / 16384;
    u->e2[1] = (e3[2] * u->e1[0] - e3[0] * u->e1[2]) / 16384;
    u->e2[2] = (e3[0] * u->e1[1] - e3[1] * u->e1[0]) / 16384;
    u->acc[0] = u->acc[1] = 0;
}

/* tilt -> pixels this tick (joystick: tilt = velocity) */
static int mouse_axis(ui_t *u, const int32_t *e, int i)
{
    int32_t v = 0;
    for (int k = 0; k < 3; k++) v += (u->g[k] >> 4) * e[k];
    v >>= 14;                                          /* ≈ 2048·sin(tilt) */
    int32_t dz = u->dz * 36, mag = v < 0 ? -v : v;
    if (mag <= dz) { u->acc[i] = 0; return 0; }
    mag -= dz;
    if (mag > 1448) mag = 1448;                        /* 45° */
    int32_t f = (mag * (100 - u->curve) + mag * mag / 1448 * u->curve) / 100;
    int32_t q8 = f * u->speed * 27 / 512;              /* speed 100, 45°: ~30 px per 20 ms */
    u->acc[i] += v < 0 ? -q8 : q8;
    int32_t px = u->acc[i] / 256;
    u->acc[i] -= px * 256;
    if (px > 127) px = 127;
    if (px < -127) px = -127;
    return (int)px;
}

static void mode_banner(const char *t) { SCREEN_WAKE(); UI_IDLE = 0; draw(t); }

static void set_mode(int m)
{
    ui_t *u = UIS;
    if (m == u->mode) return;
    if (u->mode == MODE_MOUSE && u->btn) { MOUSE_REPORT(0, 0, 0, 0); u->btn = 0; }
    if (u->mode == MODE_HEART) heart_set(0);
    u->mode = (uint8_t)m; u->pend = 0;
    if (m == MODE_MOUSE) { u->need_neutral = 1; u->show = 75; ptr_reset(); mode_banner("MOUSE"); timer_need(); }
    else if (m == MODE_KEYS) { u->show = 75; u->down_t = 0; ptr_reset(); mode_banner(" KEYS"); timer_need(); }
    else if (m == MODE_HEART) heart_set(1);
    else { u->show = 0; SCREEN_WAKE(); UI_IDLE = 0; CLOCK_DRAW(); }
    ui_event(0x7A14, m);
}

/* keep the 12.5 ms BLE interval while the mouse is on (the stock would drop to 187 ms after ~20 s) */
static void link_keep_fast(ptr_t *p)
{
    if (!LINK_UP) { p->fast = 0; return; }
    if (!p->fast || !LINK_FAST) { REQ_FAST(); p->fast = 1; }
    LINK_FAST = 1; LINK_CNT = 0;
}

static int clamp127(int v) { return v > 127 ? 127 : (v < -127 ? -127 : v); }

/* send what is waiting, but only when the BLE queue has drained: a report never waits
 * behind a backlog (that backlog was the multi-second lag) */
static void mouse_flush(ui_t *u, ptr_t *p, int force)
{
    if (!p->out[0] && !p->out[1]) { p->nosend = 0; return; }
    int f = BLE_FREE();
    if (f > p->fmax) p->fmax = (uint8_t)(f > 255 ? 255 : f);
    if (!force && f + 1 < p->fmax && p->nosend < 5) { p->nosend++; return; }
    int dx = clamp127(p->out[0]), dy = clamp127(p->out[1]);
    MOUSE_REPORT(u->btn, dx, dy, 0);
    p->out[0] = (int16_t)(p->out[0] - dx); p->out[1] = (int16_t)(p->out[1] - dy);
    p->nosend = 0;
}

static void mouse_tick(ui_t *u)
{
    ptr_t *p = ptr();
    link_keep_fast(p);
    if (u->need_neutral >= 13) { u->need_neutral = 0; mouse_recenter(u); p->have = 0; }
    int dx = 0, dy = 0;
    if (u->speed && !u->need_neutral) {
        int a, b;
        if (u->mflags & MF_JOY) { a = mouse_axis(u, u->e1, 0); b = mouse_axis(u, u->e2, 1); }
        else {
            a = (int)(u->acc[0] / 256); u->acc[0] -= a * 256;
            b = (int)(u->acc[1] / 256); u->acc[1] -= b * 256;
        }
        if (u->mflags & MF_SWAP) { int t = a; a = b; b = t; }
        dx = (u->mflags & MF_INVX) ? -a : a;
        dy = (u->mflags & MF_INVY) ? -b : b;
    }
    if (u->pend) {                                                   /* press not decided yet: hold the motion */
        int hx = p->hold[0] + dx, hy = p->hold[1] + dy;
        p->hold[0] = (int16_t)clamp127(hx); p->hold[1] = (int16_t)clamp127(hy);
        if (hx > T_DRAG || hx < -T_DRAG || hy > T_DRAG || hy < -T_DRAG) {   /* press + move = drag */
            u->pend = 0; u->btn = 1; u->moved = 1;
            MOUSE_REPORT(1, 0, 0, 0);
            p->out[0] = (int16_t)(p->out[0] + p->hold[0]); p->out[1] = (int16_t)(p->out[1] + p->hold[1]);
            p->hold[0] = p->hold[1] = 0;
        } else if (u->press_t >= u->rc_ticks) {                      /* press + hold still = right click */
            u->pend = 0; p->hold[0] = p->hold[1] = 0;
            MOUSE_REPORT(2, 0, 0, 0); MOUSE_REPORT(0, 0, 0, 0);
            ui_event(0x7A15, TK_RIGHT);
            return;
        }
    } else {
        int ox = p->out[0] + dx, oy = p->out[1] + dy;                /* backlog cap: ~8 reports */
        p->out[0] = (int16_t)(ox > 1000 ? 1000 : (ox < -1000 ? -1000 : ox));
        p->out[1] = (int16_t)(oy > 1000 ? 1000 : (oy < -1000 ? -1000 : oy));
    }
    mouse_flush(u, p, 0);
    if (u->show && --u->show == 0) UI_IDLE = 600;                   /* "MOUSE" banner off */
}

/* edges from the 20 ms poll */
static void touch_press(ui_t *u)
{
    u->press_t = 0; u->hold_done = 0; u->moved = 0;
    if (u->rel_t > T_BURST) u->taps = 0;
    if (u->mode == MODE_MOUSE) {
        ptr_t *p = ptr();
        p->hold[0] = p->hold[1] = 0;
        if (u->speed == 0) { u->btn = 1; MOUSE_REPORT(1, 0, 0, 0); }  /* pure clicker (pointer from elsewhere) */
        else u->pend = 1;
    } else if (u->mode == MODE_CLOCK && !GS->msg) {
        if (!UI_SCREEN_ON) SCREEN_WAKE();                           /* wake, or restart the timer: never stacks */
        UI_IDLE = 0;
        CLOCK_DRAW();
    }
    touch_event(TK_HOLD_START);
}

static void touch_release(ui_t *u)
{
    int tap = u->press_t < T_TAP;
    if (u->mode == MODE_MOUSE) {
        ptr_t *p = ptr();
        if (u->pend) { MOUSE_REPORT(1, 0, 0, 0); MOUSE_REPORT(0, 0, 0, 0); }   /* click where the press started */
        else if (u->btn) { mouse_flush(u, p, 1); MOUSE_REPORT(0, 0, 0, 0); }
        u->pend = 0; u->btn = 0; p->hold[0] = p->hold[1] = 0;
    }
    u->rel_t = 0;
    touch_event(tap ? TK_TAP : TK_HOLD_END);
    if (u->mode == MODE_KEYS) return;              /* keys: typing fast must never switch modes */
    if (tap && ++u->taps >= 5) {
        u->taps = 0;
        ui_event(0x7A15, TK_FIVE);
        set_mode(u->mode == MODE_MOUSE ? MODE_CLOCK : MODE_MOUSE);
    }
}

/* keys mode: hold the 12.5 ms link (press timing for glide typing), leave after 2 s unlinked */
static void keys_tick(ui_t *u)
{
    ptr_t *p = ptr();
    link_keep_fast(p);
    if (u->show && --u->show == 0) UI_IDLE = 600;                   /* "KEYS" banner off */
    if (LINK_UP) u->down_t = 0;
    else if (++u->down_t >= KEYS_DROP) set_mode(MODE_CLOCK);
}

static void touch_tick(void)
{
    ui_t *u = UIS;
    u->tick++;
    if (!STOCK_AWAKE()) return;                    /* powered-off state: stock long-press turns it on */
    int p = GPIO_READ(TOUCH_PIN) != 0;
    if (p && !u->pressed) { u->pressed = 1; touch_press(u); }
    else if (!p && u->pressed) { u->pressed = 0; touch_release(u); }
    else if (p) {
        if (u->press_t < 0xffff) u->press_t++;
        if (u->press_t >= T_LONG && !u->hold_done && u->mode != MODE_MOUSE && u->mode != MODE_KEYS) {
            u->hold_done = 1; u->taps = 0;
            ui_event(0x7A15, TK_LONG);
            set_mode(u->mode == MODE_HEART ? MODE_CLOCK : MODE_HEART);
        }
    } else if (u->rel_t < 0xffff) u->rel_t++;
    if (u->mode == MODE_MOUSE) mouse_tick(u);
    else if (u->mode == MODE_KEYS) keys_tick(u);
}

/* ===================================================================== hooks */

void app_tick(void)
{
    ui_init();
    gest_tick();
    touch_tick();
    if (G2->heart & H_ON) heart_tick();
}

/* replaces the four `bl 0x100068ec` (stock drain restart: boot, wake, power
 * events). While the gesture detector or recorder owns the accel, the stock
 * drain stays off and we re-apply our ODR instead. */
void hook_drain_start(void)
{
    gest_init(); ui_init();
    timer_need();                                  /* touch is ours: the tick always runs */
    if (gest_want()) { GS->flags |= GF_REINIT; return; }
    DRAIN_START();
}

/* replaces `bl 0x1000d80c` at 0x10009764: called once per accel FIFO sample
 * from the stock 500 ms drain */
void hook_accel(int x, int y, int z)
{
    (void)x; (void)y; (void)z;                     /* build 12+: no stock knock macros */
    gest_init(); ui_init();
    timer_need();                                  /* our tick owns touch (and the FIFO if wanted) */
}

/* replaces the four `bl 0x1001c560` in the touch scanner */
void hook_key(int ev)
{
    if (!STOCK_AWAKE()) STOCK_KEY(ev);             /* powered off: let stock turn the ring on */
    /* otherwise swallowed: no stock page menu; touch_tick() implements our behavior */
}

/* replaces `bl 0x10016a38` at 0x100167ea: PPG FIFO read during HR measurement */
int hook_ppg(void *arg)
{
    int r = STOCK_PPG(arg);
    int n = PPG_COUNT;
    if (n > 34) {                                  /* would have spilled past the guard */
        if (G2->heart & H_ON) { memset(HRT, 0, sizeof(heart_t)); memset(HRT2, 0, sizeof(heart2_t)); }
        return r;
    }
    if (r && (G2->heart & H_ON)) { heart_batch(HRT, PPG_DATA, n); HRT->flags |= HF_SEND; }
    return r;
}

/* replaces `bl 0x1000e1c8` at 0x1001925a (start of draw_text): while heart mode
 * owns the screen, stock UI drawing is skipped */
int hook_draw_gate(void)
{
    if ((G2->heart & (H_ON | H_DRAW)) == H_ON) return 0;
    if (GS->magic == G_MAGIC && GS->msg && !(G2->heart & H_DRAW)) return 0;
    return STOCK_AWAKE();
}

/* replaces `bl 0x10012230` at 0x1001330e: every validated AB frame from the app */
void hook_cmd(const uint8_t *f, int len)
{
    int clen = (f[2] << 8) | f[3];
    if (len >= 9 && clen >= 3 && f[6] == 0x7A) {
        const uint8_t *p = f + 9;
        int n = clen - 3;
        if (f[7] == 0x02) {
            char t[6] = { 0, 0, 0, 0, 0, 0 };
            for (int i = 0; i < n && i < 5; i++) {
                uint8_t ch = p[i];
                if (ch >= 'a' && ch <= 'z') ch = (uint8_t)(ch - 32);
                t[i] = (ch >= 0x20 && ch < 0x7f) ? (char)ch : ' ';
            }
            SCREEN_WAKE();
            draw(t);
        } else if (f[7] == 0x03 && n >= 1) {
            ui_init();
            set_mode(p[0] ? MODE_HEART : MODE_CLOCK);
        } else if (f[7] == 0x09 && n >= 1) {
            ui_init();
            if (p[0] <= MODE_KEYS) set_mode(p[0]);
            rec_status(-1);
        } else if (f[7] == 0x0A && n >= 5) {
            /* mouse settings: speed 0..100 (0 = clicker only), curve 0..100, deadzone deg, flags, right-click ticks */
            ui_t *u = UIS; ui_init();
            u->speed = p[0] > 100 ? 100 : p[0];
            u->curve = p[1] > 100 ? 100 : p[1];
            u->dz = p[2] > 20 ? 20 : p[2];
            u->mflags = p[3] & 15;
            ptr_reset();
            u->rc_ticks = p[4] < 10 ? 10 : p[4];
        } else if (f[7] == 0x0B) {
            ui_init();
            mouse_recenter(UIS);
        } else if (f[7] == 0x04 && n >= 1) {
            /* 7A 04 00 [on] [odr]: recorder; the accel runs at ~100 Hz while anything owns it */
            gest_init();
            int on = p[0] != 0;
            if (on && !G->rec) {
                G->rec = 1; G->seq = 0; GS->nbuf = 0;
                timer_need();                      /* gest_tick takes the FIFO */
                rec_status(-1);
            } else if (!on && G->rec) {
                G->rec = 0;
                if (GS->nbuf) { rec_send((const int16_t (*)[3])GS->buf, GS->nbuf); GS->nbuf = 0; }
                rec_status(-1);
            }
        } else if (f[7] == 0x05 && n >= 1) {
            gest_init();
            if (p[0]) { GS->flags &= (uint8_t)~GF_OFF; timer_need(); }
            else { GS->flags |= GF_OFF; GS->armed = 0; }
            rec_status(-1);
        } else if (f[7] == 0x06) {
            gest_init();
            timer_need();
            gest_fire();
        } else if (f[7] == 0x07 && n >= 1) {
            gest_init();
            msg_t *m = MS;
            for (int i = p[0], k = 1; i < M_MAX && k + 5 <= n; i++, k += 5)
                for (int c = 0; c < 5; c++) {
                    uint8_t ch = p[k + c];
                    if (ch >= 'a' && ch <= 'z') ch = (uint8_t)(ch - 32);
                    m->f[i][c] = (ch >= 0x20 && ch < 0x7f) ? (char)ch : ' ';
                }
        } else if (f[7] == 0x08 && n >= 3) {
            gest_init();
            msg_t *m = MS;
            GS->msg = 0;                           /* stop a playback using the old frames */
            m->n = p[0] > M_MAX ? M_MAX : p[0];
            m->tpf = p[1] ? p[1] : 1;
            m->reps = p[2] < 1 ? 1 : (p[2] > 20 ? 20 : p[2]);
            rec_status(-1);
        }
        return;   /* our group (incl. retired 7A 01): never handed to the stock processor */
    }
    STOCK_CMD(f, len);
}
