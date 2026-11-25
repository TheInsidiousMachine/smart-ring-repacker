/* Host test of the firmware glue with mocked stock functions. */
#include <math.h>
#include <stdio.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdlib.h>

static uint8_t hid_mode, meas_mode, ui_icon, ppg_count;
static uint16_t ui_idle; static uint32_t ui_on = 1;
static int32_t ppg_data[40];
static _Alignas(8) uint8_t a_mem[64];
static _Alignas(8) uint8_t c_mem[32];
static void (*timer_cb)(void);
static int taps, keys, creates, starts, stops, wakes, cmds, meas_starts, meas_stops, draws_ok, draws_blocked;
static int touch;
static long cx, cy; static int btn, clicks, reports;
static double pitch;
static int pending;
static char shown[8]; static int shown_page;
static int last_icon;

void hook_accel(int x, int y, int z);
int hook_draw_gate(void);
__attribute__((unused)) static void m_tap(int x, int y, int z) { (void)x; (void)y; (void)z; taps++; }
static void m_key(int ev) { (void)ev; keys++; }
static int16_t ax[32], ay[32], az[32]; static uint8_t acc_count;
static int sent_frames, sent_len, sent_id;
/* stock 500 ms drain: whatever is left in the FIFO goes through hook_accel */
static void m_drain(void)
{
    while (pending > 0) {
        pending--;
        double gy = sin(pitch) * 1000, gz = cos(pitch) * 1000;
        hook_accel(0, (int)lround(gy * 2.048), (int)lround(gz * 2.048));
    }
}
/* replay of a real recording (raw>>2 units), 2 samples per 20 ms (~100 Hz) */
static int16_t (*rp)[3]; static int rp_n, rp_pos, rp_on;
/* direct FIFO read used by the mouse */
static int m_acc_read(void)
{
    int n = 0;
    if (rp_on) {
        while (pending > 0 && n < 32 && rp_pos < rp_n) {
            pending--;
            ax[n] = (int16_t)(rp[rp_pos][0] * 4); ay[n] = (int16_t)(rp[rp_pos][1] * 4); az[n] = (int16_t)(rp[rp_pos][2] * 4);
            rp_pos++; n++;
        }
        acc_count = (uint8_t)n;
        return n;
    }
    while (pending > 0 && n < 32) {
        pending--;
        double gy = sin(pitch) * 1000, gz = cos(pitch) * 1000;
        ax[n] = 0; ay[n] = (int16_t)(lround(gy * 2.048) * 4); az[n] = (int16_t)(lround(gz * 2.048) * 4);
        n++;
    }
    acc_count = (uint8_t)n;
    return n;
}
static int raw_frames, rec_samples, n_fired, n_armed; static double fired_t[64];
static int drain_on = 1, status_frames; static uint8_t acc_reg[0x80] = { [0x20] = 0x47 };
static int m_acc_wr(int dev, int reg, int val) { (void)dev; acc_reg[reg & 0x7f] = (uint8_t)val; return 1; }
static int m_acc_rd(int dev, int reg, int len, uint8_t *b) { (void)dev; for (int i = 0; i < len; i++) b[i] = acc_reg[(reg + i) & 0x7f]; return 1; }
static void m_drain_stop(void) { drain_on = 0; }
static void m_drain_start(void) { drain_on = 1; }
static int diag_frames, diag_samples;
static int touch_n, touch_kind[64], touch_t[64], touch_len, mode_ev = -1;
static int m_send(int flag, int id, int kf, const uint8_t *d, int len)
{
    (void)flag; (void)kf;
    if (id == 0x7A11) { rec_samples += d[1]; raw_frames++; return 1; }
    if (id == 0x7A12) { status_frames++; return 1; }
    if (id == 0x7A13) { if (d[0] == 2 && n_fired < 64) fired_t[n_fired++] = rp_pos / 100.0; if (d[0] == 1) n_armed++; return 1; }
    if (id == 0x7A16) { diag_frames++; diag_samples += len >= 6 ? (len - 6) / 6 : 0; return 1; }
    if (id == 0x7A15) { if (touch_n < 64) { touch_kind[touch_n] = d[0]; touch_t[touch_n] = len >= 3 ? d[1] | d[2] << 8 : -1; touch_n++; } touch_len = len; return 1; }
    if (id == 0x7A14) { mode_ev = d[0]; return 1; }
    sent_frames++; sent_len = len; sent_id = id; return 1;
}
/* like the stock timer code: create writes the index at +0x16..0x19, start writes +0x00..0x13 */
static int m_create(void *const *h, int rep, void (*cb)(void)) { uint8_t *t = (uint8_t *)*h; (void)rep; t[0x15] = 1; memset(t + 0x16, 0x5a, 4); timer_cb = cb; creates++; return 1; }
static int m_start(void *t, int ticks, int z) { (void)z; (void)ticks; memset(t, 0xa5, 0x14); ((uint8_t *)t)[0x14] = 1; starts++; return 1; }
__attribute__((unused)) static int m_stop(void *t) { ((uint8_t *)t)[0x14] = 0; stops++; return 1; }
static int m_gpio(int pin) { return pin == 10 ? touch : 0; }
static int rclicks;
static void m_report(int b, int dx, int dy, int w) { (void)w; cx += dx; cy += dy; if ((b & 1) && !(btn & 1)) clicks++; if ((b & 2) && !(btn & 2)) rclicks++; btn = b; reports++; }
static int clock_draws;
static void m_wake(void) { wakes++; ui_on = 1; }
static int awake = 1;
static int m_awake(void) { return awake; }
/* stock draw_text: gate first, then copy 5 chars + icon */
static void m_draw(const char *s, int page)
{
    if (!hook_draw_gate() || !ui_on) { draws_blocked++; return; }
    memcpy(shown, s, 5); shown[5] = 0; ui_icon = (uint8_t)s[5]; shown_page = page; draws_ok++;
}
static void m_cmd(const uint8_t *f, int len) { (void)f; (void)len; cmds++; }
static void m_start_meas(int m) { meas_mode = (uint8_t)m; meas_starts++; }
static void m_stop_meas(int m) { if (meas_mode == m) meas_mode = 0; meas_stops++; }
static int m_ppg(void *arg) { (void)arg; return ppg_count > 0; }

#define STOCK_TAP m_tap
#define STOCK_KEY m_key
#define ACC_READ m_acc_read
#define DRAIN_STOP m_drain_stop
#define ACC_WR m_acc_wr
#define ACC_RD m_acc_rd
#define DRAIN_START m_drain_start
#define ACC_COUNT acc_count
#define ACC_X ax
#define ACC_Y ay
#define ACC_Z az
#define SEND_FRAME m_send
#define STOCK_CMD m_cmd
#define STOCK_PPG m_ppg
#define STOCK_AWAKE m_awake
#define START_MEAS m_start_meas
#define STOP_MEAS m_stop_meas
#define TIMER_CREATE m_create
#define TIMER_START m_start
#define TIMER_STOP m_stop
#define GPIO_READ m_gpio
#define MOUSE_REPORT m_report
#define SCREEN_WAKE m_wake
#define DRAW_TEXT m_draw
static void m_clock(void) { m_draw("12:34", 1); clock_draws++; }
#define CLOCK_DRAW m_clock
#define HID_MODE hid_mode
#define MEAS_MODE meas_mode
#define PPG_COUNT ppg_count
#define PPG_DATA ppg_data
static int32_t ppg_data2[40]; static uint8_t ppg_wear = 1;
#define PPG_DATA2 ppg_data2
#define PPG_WEAR ppg_wear
static uint16_t stock_hr;
#define STOCK_HR stock_hr
#define UI_IDLE ui_idle
#define UI_SCREEN_ON ui_on
#define UI_ICON ui_icon
#define HRT ((heart_t *)(a_mem + 8))
static _Alignas(8) uint8_t b_mem[16];
#define HRT2 ((heart2_t *)b_mem)
#define G ((glue_t *)(c_mem + 0x1a))
#define G2 G
#define TMR (c_mem)
static _Alignas(8) uint8_t d_mem[0x60];
#define GS ((gest_t *)d_mem)
static _Alignas(8) uint8_t e_mem[0xa8];
#define MS ((msg_t *)e_mem)
static _Alignas(8) uint8_t f_mem[0x60];
#define UIS ((ui_t *)f_mem)
static _Alignas(8) uint8_t g_mem[0x10];
#define GS2 ((gest2_t *)g_mem)
static _Alignas(8) uint8_t h_mem[0x40];
#define PTR ((ptr_t *)h_mem)
static uint8_t link_up = 1, link_fast, link_cnt; static int fast_reqs, ble_free = 10;
static void m_req_fast(void) { fast_reqs++; link_fast = 1; }
static int m_ble_free(void) { return ble_free; }
#define LINK_UP link_up
#define LINK_FAST link_fast
#define LINK_CNT link_cnt
#define REQ_FAST m_req_fast
#define BLE_FREE m_ble_free
#include "ring_app.c"
static int tmr_mem_on(void);

static int fails;
static void fails_extern(void) { fails++; }
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static int tmr_mem_on(void) { return c_mem[0x14]; }
static int now_ms;
static void run(int ms)
{
    for (int i = 0; i < ms / 20; i++) {
        now_ms += 20; pending++;
        if (tmr_mem_on() && timer_cb) timer_cb();
        if (now_ms % 500 == 0 && drain_on) m_drain();
    }
}

/* ---- synthetic PPG: 25 Hz, delivered in batches of 25 every 1000 ms ---- */
static double ppg_phase, ppg_bpm = 72, ppg_target = 72;
static double beat_times[400]; static int nbeat_times;
static double ppg_t;      /* seconds */
static double ppg_value(double dt)
{
    if (ppg_bpm < ppg_target) { ppg_bpm += 7.6 * dt; if (ppg_bpm > ppg_target) ppg_bpm = ppg_target; }
    double prev = ppg_phase;
    ppg_phase += dt * ppg_bpm / 60.0;
    if (floor(ppg_phase) != floor(prev) && nbeat_times < 400) beat_times[nbeat_times++] = ppg_t;
    double ph = ppg_phase - floor(ppg_phase);
    /* reflected light: sharp drop at systole (first 15%), slow recovery */
    double pulse = ph < 0.15 ? -ph / 0.15 : -1.0 + (ph - 0.15) / 0.85;
    double noise = ((rand() / (double)RAND_MAX) * 2 - 1) * 0.08;
    return 400000 + 3000 * (pulse + noise) + 2000 * sin(ppg_t * 0.3);
}

/* run heart mode for `secs`, delivering PPG batches; record flash times */
static double flashes[400]; static int nflash; static int bpm_seen[400];
static void run_heart(int secs)
{
    for (int ms = 0; ms < secs * 1000; ms += 20) {
        now_ms += 20;
        if (c_mem[0x14] && timer_cb) timer_cb();
        int icon = ui_icon == 'r';
        if (icon && !last_icon && nflash < 400) { bpm_seen[nflash] = HRT->bpm; flashes[nflash++] = now_ms / 1000.0; }
        last_icon = icon;
        if (now_ms % 1000 == 0) {
            for (int i = 0; i < 25; i++) { ppg_t += 0.04; ppg_data[i] = (int32_t)ppg_value(0.04); }
            ppg_count = 25;
            hook_ppg(0);
        }
    }
}

static int replay(const char *name)
{
    char path[256]; snprintf(path, sizeof path, "testdata/%s.txt", name);
    FILE *f = fopen(path, "r");
    if (!f) { printf("missing %s\n", path); fails_extern(); return -1; }
    static int16_t buf[20000][3]; int n = 0, x, y, z;
    while (n < 20000 && fscanf(f, "%d %d %d", &x, &y, &z) == 3) { buf[n][0] = (int16_t)x; buf[n][1] = (int16_t)y; buf[n][2] = (int16_t)z; n++; }
    fclose(f);
    rp = buf; rp_n = n; rp_pos = 0; rp_on = 1; n_fired = 0; n_armed = 0; pending = 0;
    while (rp_pos < rp_n) {
        now_ms += 20; pending += 2;
        if (c_mem[0x14] && timer_cb) timer_cb();
        if (now_ms % 500 == 0 && drain_on) m_drain();
    }
    rp_on = 0; pending = 0;
    return n_fired;
}

int main(void)
{
    /* 0. boot: the stock drain restart is redirected to us; detector owns the accel */
    hook_drain_start();
    CHECK(drain_on == 1 && c_mem[0x14] && GS->magic == G_MAGIC, "boot: timer should start, gesture state init");
    m_drain_stop(); /* (stock had not started it yet in the real boot; the mock starts 'on') */
    run(100);
    CHECK(acc_reg[0x20] == 0x67 && (GS->flags & GF_OWN), "detector should own the accel at 100 Hz: %#x", acc_reg[0x20]);
    /* gesture off -> pure stock behavior */
    { uint8_t off[] = { 0xAB, 1, 0, 4, 0, 0, 0x7A, 0x05, 0x00, 0 }; hook_cmd(off, sizeof off); }
    run(100);
    CHECK(drain_on && acc_reg[0x20] == 0x47 && c_mem[0x14], "gesture off should hand the accel back to stock (tick keeps running for touch)");
    hook_drain_start();
    CHECK(drain_on, "gesture off: stock drain restart must pass through");

    /* 1. clock mode: press wakes the clock; press while on restarts the timer (never stacks); no stock menu */
    run(1000);
    CHECK(taps == 0, "stock knock macros must never run");
    hook_key(0); CHECK(keys == 0, "stock touch menu must be swallowed while awake");
    awake = 0; hook_key(1); CHECK(keys == 1, "powered off: stock must get the key (long press = power on)"); awake = 1;
    ui_on = 0; int w0 = wakes;
    touch = 1; run(40); touch = 0; run(400);
    CHECK(wakes == w0 + 1 && clock_draws == 1 && ui_idle == 0 && strcmp(shown, "12:34") == 0, "press should wake to the clock");
    ui_idle = 3; touch = 1; run(40); touch = 0; run(400);
    CHECK(ui_idle == 0 && clock_draws == 2 && wakes == w0 + 1, "press while on should restart the timer without re-waking (idle %d)", ui_idle);
    CHECK(UIS->mode == MODE_CLOCK && reports == 0, "clock mode must not send mouse reports");

    /* 2. five taps = mouse mode. Build 14 pointer: cursor follows tilt CHANGES and stops when the
     *    hand stops; tap = click, press + hold still = right click, press + move = drag; link kept fast */
    {
        link_fast = 0; link_cnt = 7;
        for (int i = 0; i < 5; i++) { touch = 1; run(60); touch = 0; run(100); }
        CHECK(UIS->mode == MODE_MOUSE && strcmp(shown, "MOUSE") == 0, "5 taps should enter mouse mode (mode %d, '%s')", UIS->mode, shown);
        CHECK(fast_reqs == 1 && link_fast == 1 && link_cnt == 0, "mouse mode must ask for the fast BLE interval once (%d)", fast_reqs);
        int c0 = clicks;
        pitch = 0; run(600);                       /* neutral captured, level hand = still */
        long y0 = cy, x0 = cx;
        run(1000);
        CHECK(cy == y0 && cx == x0, "level hand must not move the cursor (%ld,%ld)", cx - x0, cy - y0);
        for (int i = 1; i <= 25; i++) { pitch = 0.35 * i / 25; run(20); }   /* tilt 20 deg in 0.5 s */
        run(300);
        long y1 = cy;
        printf("pointer: tilt 20 deg in 0.5 s moved the cursor (%ld, %ld)\n", cx - x0, cy - y0);
        CHECK(cy - y0 > 50 && cx == x0, "tilt should move along one axis only");
        run(1000);
        CHECK(cy == y1, "holding the tilt must NOT keep moving (pointer) (%ld)", cy - y1);
        CHECK(fast_reqs == 1 && link_cnt == 0, "fast link held without repeated requests");
        for (int i = 1; i <= 25; i++) { pitch = 0.35 - 0.35 * i / 25; run(20); }
        run(300);
        CHECK(cy < y1 - 50, "tilting back moves back (%ld)", cy - y1);
        long y2 = cy;
        touch = 1; run(60); touch = 0; run(300);
        CHECK(clicks == c0 + 1 && btn == 0 && cy == y2, "tap = left click, cursor still (clicks %d)", clicks - c0);
        touch = 1; run(900); touch = 0; run(300);
        CHECK(rclicks == 1 && clicks == c0 + 1 && btn == 0, "hold still 0.7 s = right click (r %d, l %d)", rclicks, clicks - c0);
        touch = 1; run(100);
        for (int i = 1; i <= 15; i++) { pitch = 0.3 * i / 15; run(20); }
        run(200);
        CHECK(btn == 1 && cy > y2 + 20, "press + move = drag with the button down (%ld)", cy - y2);
        touch = 0; run(300);
        CHECK(btn == 0 && rclicks == 1 && clicks == c0 + 2, "release ends the drag");
        for (int i = 1; i <= 15; i++) { pitch = 0.3 - 0.3 * i / 15; run(20); }
        run(300);
        /* backpressure: a full BLE queue holds reports back (max 100 ms), nothing is lost */
        { long yb = cy; int r0 = reports; ble_free = 4;
          for (int i = 1; i <= 10; i++) { pitch = 0.2 * i / 10; run(20); }
          int during = reports - r0; ble_free = 10; run(400);
          CHECK(during <= 3 && cy > yb + 20, "busy link: few reports while full (%d), motion delivered after (%ld)", during, cy - yb);
          for (int i = 1; i <= 10; i++) { pitch = 0.2 - 0.2 * i / 10; run(20); }
          run(300); }
        /* joystick style (flags 8): holding a tilt keeps moving */
        uint8_t mj[] = { 0xAB, 1, 0, 8, 0, 0, 0x7A, 0x0A, 0x00, 50, 50, 4, 8, 35 }; hook_cmd(mj, sizeof mj);
        { uint8_t rc[] = { 0xAB, 1, 0, 3, 0, 0, 0x7A, 0x0B, 0x00 }; hook_cmd(rc, sizeof rc); }
        run(200); long yj = cy; pitch = 0.35; run(500); long yj1 = cy; run(500);
        CHECK(cy > yj1 && yj1 > yj, "joystick: held tilt keeps moving (%ld, %ld)", yj1 - yj, cy - yj1);
        pitch = 0; run(300);
        /* speed 0 = pure clicker (pointer from eye tracking): press = down, release = up */
        uint8_t ms0[] = { 0xAB, 1, 0, 8, 0, 0, 0x7A, 0x0A, 0x00, 0, 50, 4, 0, 35 }; hook_cmd(ms0, sizeof ms0);
        long y3 = cy; int r1 = rclicks; pitch = 0.35; touch = 1; run(1000);
        CHECK(btn == 1 && cy == y3 && rclicks == r1, "clicker: held = button down, no motion, no right click");
        touch = 0; pitch = 0; run(100); CHECK(btn == 0, "clicker: release = up");
        uint8_t ms1[] = { 0xAB, 1, 0, 8, 0, 0, 0x7A, 0x0A, 0x00, 50, 50, 4, 0, 35 }; hook_cmd(ms1, sizeof ms1);
        for (int i = 0; i < 5; i++) { touch = 1; run(60); touch = 0; run(100); }
        CHECK(UIS->mode == MODE_CLOCK && clock_draws >= 3, "5 taps again = back to the clock");
        run(500);
    }

    /* 2b. hold 2 s = heartbeat mode on, again = off */
    {
        touch = 1; run(2100); touch = 0; run(100);
        CHECK(UIS->mode == MODE_HEART && meas_mode == HR_MODE && (G2->heart & H_ON), "hold 2 s should start heart mode");
        touch = 1; run(2100); touch = 0; run(100);
        CHECK(UIS->mode == MODE_CLOCK && meas_mode == 0 && !(G2->heart & H_ON), "hold 2 s again should stop heart mode");
    }

    /* 2c. build 16 keys mode (gaze keyboard): touch edges stamped with the 20 ms tick, fast
     *     link held, no HID, no mode shortcuts; leaves after 2 s without a link */
    {
        int r0 = reports, c0 = clicks;
        link_fast = 0; link_cnt = 9; int fr0 = fast_reqs;
        uint8_t k3[] = { 0xAB, 1, 0, 4, 0, 0, 0x7A, 0x09, 0x00, 3 }; hook_cmd(k3, sizeof k3);
        run(100);
        CHECK(UIS->mode == MODE_KEYS && mode_ev == MODE_KEYS && strcmp(shown, " KEYS") == 0, "7A 09 3 should enter keys mode (mode %d)", UIS->mode);
        CHECK(fast_reqs == fr0 + 1 && link_fast == 1 && link_cnt == 0, "keys mode must ask for the fast link once (%d)", fast_reqs - fr0);
        int n0 = touch_n;
        touch = 1; run(20);
        CHECK(touch_n == n0 + 1 && touch_kind[n0] == TK_HOLD_START && touch_len == 3, "press must be sent on the first tick, 3 bytes");
        run(40); touch = 0; run(20);
        CHECK(touch_n == n0 + 2 && touch_kind[n0 + 1] == TK_TAP, "short press = TAP");
        int dt = (touch_t[n0 + 1] - touch_t[n0]) & 0xffff;
        CHECK(dt == 3, "stamps should be 3 ticks apart (60 ms press), got %d", dt);
        for (int i = 0; i < 6; i++) { touch = 1; run(60); touch = 0; run(80); }
        CHECK(UIS->mode == MODE_KEYS, "6 quick taps must not leave keys mode");
        int n1 = touch_n;
        touch = 1; run(2600); touch = 0; run(40);
        CHECK(UIS->mode == MODE_KEYS && !(G2->heart & H_ON), "a 2.6 s glide must not toggle heart mode");
        CHECK(touch_kind[n1] == TK_HOLD_START && touch_kind[touch_n - 1] == TK_HOLD_END && touch_n == n1 + 2, "glide = HOLD_START then HOLD_END only");
        CHECK(((touch_t[n1 + 1] - touch_t[n1]) & 0xffff) == 130, "glide stamps should be 130 ticks apart, got %d", (touch_t[n1 + 1] - touch_t[n1]) & 0xffff);
        CHECK(reports == r0 && clicks == c0, "keys mode must never send HID reports");
        link_cnt = 25; run(40);
        CHECK(link_fast == 1 && link_cnt == 0 && fast_reqs == fr0 + 1, "fast link held without new requests");
        link_up = 0; run(1900);
        CHECK(UIS->mode == MODE_KEYS, "a short link drop keeps keys mode");
        link_up = 1; run(100); link_up = 0; run(2100);
        CHECK(UIS->mode == MODE_CLOCK, "2 s without a link should fall back to the clock");
        link_up = 1; run(200);
    }

    /* 3. recorder: 50 Hz samples streamed as 7A 11, stock drain paused, status frames */
    {
        uint8_t on[] = { 0xAB, 1, 0, 5, 0, 0, 0x7A, 0x04, 0x00, 1, 6 };
        int st0 = status_frames;
        hook_cmd(on, sizeof on);
        CHECK(status_frames == st0 + 1, "status sent");
        run(20);                                   /* next tick takes the FIFO */
        CHECK(!drain_on, "drain should pause");
        CHECK(acc_reg[0x20] == 0x67, "fast ODR not written: %#x", acc_reg[0x20]);
        int s0 = rec_samples; pending = 0;
        run(2000);
        CHECK(rec_samples - s0 >= 90 && rec_samples - s0 <= 101, "expected ~100 samples in 2 s, got %d", rec_samples - s0);
        CHECK(status_frames >= 2, "periodic status missing");
        uint8_t off[] = { 0xAB, 1, 0, 4, 0, 0, 0x7A, 0x04, 0x00, 0 };
        hook_cmd(off, sizeof off);
        int s1 = rec_samples; run(1000);
        CHECK(rec_samples - s1 <= 9 && drain_on, "recorder should stop and resume the drain");
        CHECK(acc_reg[0x20] == 0x47, "stock ODR not restored: %#x", acc_reg[0x20]);
    }

    /* 4. text */
    {
        uint8_t f[] = { 0xAB, 1, 0, 8, 0, 0, 0x7A, 0x02, 0x00, 'h', 'e', 'l', 'l', 'o' };
        hook_cmd(f, sizeof f);
        CHECK(strcmp(shown, "HELLO") == 0 && shown_page == 0x1b && cmds == 0, "text: '%s'", shown);
        uint8_t old[] = { 0xAB, 1, 0, 5, 0, 0, 0x7A, 0x01, 0x00, 1, 100 };   /* retired mouse settings */
        hook_cmd(old, sizeof old);
        CHECK(cmds == 0, "7A 01 must not reach stock");
        uint8_t s[] = { 0xAB, 1, 0, 4, 0, 0, 0x02, 0x04, 0x10, 0 };
        hook_cmd(s, sizeof s);
        CHECK(cmds == 1, "stock command not forwarded");
    }

    /* 5. heart mode on: mouse suspended, sensor started, stock drawing blocked */
    {
        uint8_t on[] = { 0xAB, 1, 0, 4, 0, 0, 0x7A, 0x03, 0x00, 1 };
        hook_cmd(on, sizeof on);
        CHECK(meas_mode == HR_MODE && G2->heart == H_ON, "heart mode not started");
        int b0 = draws_blocked;
        m_draw("12:00", 1);
        CHECK(draws_blocked == b0 + 1, "stock draw should be blocked in heart mode");
        int k0 = keys; hook_key(0); CHECK(keys == k0, "touch should be swallowed in heart mode");
        srand(3);
        run_heart(20);                     /* 72 bpm */
        CHECK(sent_frames >= 18 && sent_id == 0x7A10 && sent_len == 8 + 25 * 3, "PPG stream: %d frames len %d", sent_frames, sent_len);
        CHECK(diag_frames >= 2 * 18 && diag_samples >= 25 * 18, "PPG diagnostics 7A 16: %d frames, %d samples", diag_frames, diag_samples);
        int n72 = nflash;
        double sum = 0; int cnt = 0;
        for (int i = n72 - 10; i < n72 - 1; i++) { sum += flashes[i + 1] - flashes[i]; cnt++; }
        double mean = cnt ? sum / cnt : 0;
        printf("72 bpm: %d flashes in 20 s, mean interval %.3f s, shown bpm %d\n", n72, mean, HRT->bpm);
        CHECK(n72 >= 20 && n72 <= 26, "expected ~24 flashes at 72 bpm, got %d", n72);
        CHECK(fabs(mean - 60.0 / 72) < 0.06, "flash interval %.3f vs %.3f", mean, 60.0 / 72);
        CHECK(abs(HRT->bpm - 72) <= 3, "bpm %d", HRT->bpm);
        CHECK(ui_idle == 3, "screen idle counter should be pinned");

        /* step to 110 bpm: the number should follow within a few beats */
        ppg_target = 110;                  /* 72 -> 110 over 5 s */
        double t_step = now_ms / 1000.0 + 5.0;
        run_heart(12);
        int first_ok = -1;
        for (int i = n72; i < nflash; i++) if (abs(bpm_seen[i] - 110) <= 4) { first_ok = i; break; }
        printf("ramp 72->110 (5 s): bpm shown %d; correct %.2f s after the ramp ends\n", HRT->bpm,
               first_ok >= 0 ? flashes[first_ok] - t_step : -1.0);
        CHECK(first_ok >= 0 && flashes[first_ok] - t_step < 3.0, "bpm too slow to follow the change");
        CHECK(abs(HRT->bpm - 110) <= 4, "bpm %d after step", HRT->bpm);

        /* sensor stopped by stock: we restart it within ~3 s */
        meas_mode = 0; run_heart(4);
        CHECK(meas_mode == HR_MODE, "measurement not restarted");

        /* oversized PPG batch: state reset, no crash */
        ppg_count = 40; hook_ppg(0);
        CHECK(HRT->bpm == 0 && HRT->nibi == 0, "spill guard should reset heart state");

        /* no usable PPG beats (build 14 fallback): flash at the stock algorithm's rate, show its BPM */
        {
            ppg_count = 0; HRT->bpm = 0; HRT->nibi = 0; stock_hr = 75;   /* 75 x 0.97 = 72 bpm */
            int f0 = 0, last = ui_icon == 'r';
            for (int ms = 0; ms < 5000; ms += 20) {
                now_ms += 20; if (c_mem[0x14] && timer_cb) timer_cb();
                int ic = ui_icon == 'r'; if (ic && !last) f0++; last = ic;
            }
            CHECK(f0 >= 5 && f0 <= 7 && strncmp(shown, " 72", 3) == 0, "fallback: %d flashes in 5 s at 72 bpm, shown '%s'", f0, shown);
            stock_hr = 0;
        }
        uint8_t off[] = { 0xAB, 1, 0, 4, 0, 0, 0x7A, 0x03, 0x00, 0 };
        hook_cmd(off, sizeof off);
        CHECK(G2->heart == 0 && meas_mode == 0 && ui_icon == 0, "heart mode not stopped");
    }

    /* 7. gesture detector on the real recordings (must match the offline model) */
    {
        uint8_t on[] = { 0xAB, 1, 0, 4, 0, 0, 0x7A, 0x05, 0x00, 1 }; hook_cmd(on, sizeof on);
        hid_mode = 0; run(200);
        const double want[] = { 2.29, 5.41, 8.38, 11.47, 17.87, 20.73, 23.62, 26.74, 29.65, 32.65 };
        int nt = replay("target");
        printf("target: %d fired (%d armed):", nt, n_armed);
        for (int i = 0; i < nt; i++) printf(" %.2f", fired_t[i]);
        printf("\n");
        CHECK(nt == 10, "target should fire 10x, got %d", nt);
        for (int i = 0; i < nt && i < 10; i++) CHECK(fabs(fired_t[i] - want[i]) < 0.06, "fire %d at %.2f vs %.2f", i, fired_t[i], want[i]);
        CHECK(strstr("    HI THERE    ", shown) != NULL && strlen(shown) == 5, "message not drawn: '%s'", shown);
        const char *neg[] = { "bumps_only", "flick_only", "everyday", "multiflick" };
        for (int k = 0; k < 4; k++) { int n = replay(neg[k]); printf("%s: %d fired (%d armed)\n", neg[k], n, n_armed); CHECK(n == 0, "%s fired %d", neg[k], n); }
        /* built-in message (build 14): "HI THERE" scrolls once, 160 ms per step, then the screen is released */
        uint8_t t[] = { 0xAB, 1, 0, 3, 0, 0, 0x7A, 0x06, 0x00 }; hook_cmd(t, sizeof t);
        CHECK(strcmp(shown, "    H") == 0 && ui_idle == 0, "test fire: '%s'", shown);
        int b0 = draws_blocked; m_draw("12:00", 1); CHECK(draws_blocked == b0 + 1, "stock draw must be blocked during the message");
        run(160 * 4); CHECK(strcmp(shown, "HI TH") == 0, "scroll step 4: '%s'", shown);
        run(160 * 4); CHECK(strcmp(shown, "HERE ") == 0, "scroll step 8: '%s'", shown);
        run(160 * 3); CHECK(strcmp(shown, "E    ") == 0 && GS->msg, "last step: '%s'", shown);
        run(200); CHECK(GS->msg == 0 && ui_idle >= 60, "message should end after one scroll and let the screen sleep (idle %d)", ui_idle);

        /* custom message: 3 frames, 100 ms each, played twice */
        uint8_t m1[] = { 0xAB, 1, 0, 14, 0, 0, 0x7A, 0x07, 0x00, 0, 'h', 'e', 'l', 'l', 'o', 'W', 'O', 'R', 'L', 'D' };
        uint8_t m2[] = { 0xAB, 1, 0, 9, 0, 0, 0x7A, 0x07, 0x00, 2, ' ', '!', '!', '!', ' ' };
        uint8_t m3[] = { 0xAB, 1, 0, 6, 0, 0, 0x7A, 0x08, 0x00, 3, 5, 2 };
        hook_cmd(m1, sizeof m1); hook_cmd(m2, sizeof m2); hook_cmd(m3, sizeof m3);
        CHECK(MS->n == 3 && MS->tpf == 5 && MS->reps == 2, "message config %d %d %d", MS->n, MS->tpf, MS->reps);
        char seq[16][6]; int ns = 0; char last[6] = "";
        hook_cmd(t, sizeof t);
        for (int k = 0; k < 40 && ns < 16; k++) {
            if (strcmp(shown, last) != 0 || k == 0) { strcpy(seq[ns++], shown); strcpy(last, shown); }
            run(20);
        }
        CHECK(ns == 6 && !strcmp(seq[0], "HELLO") && !strcmp(seq[1], "WORLD") && !strcmp(seq[2], " !!! ") && !strcmp(seq[3], "HELLO") && !strcmp(seq[5], " !!! "),
              "custom sequence wrong (%d): %s %s %s", ns, seq[0], ns > 1 ? seq[1] : "", ns > 2 ? seq[2] : "");
        CHECK(GS->msg == 0, "custom message should end after 6 x 100 ms");
        /* n = 0 restores the built-in message */
        uint8_t m4[] = { 0xAB, 1, 0, 6, 0, 0, 0x7A, 0x08, 0x00, 0, 5, 1 }; hook_cmd(m4, sizeof m4);
        hook_cmd(t, sizeof t); CHECK(strcmp(shown, "    H") == 0, "default message after reset: '%s'", shown);
        run(3000);
    }

    printf(fails ? "%d FAILED\n" : "all ring_app tests passed\n", fails);
    return fails != 0;
}
