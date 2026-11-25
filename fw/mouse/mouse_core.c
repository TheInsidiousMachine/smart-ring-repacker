#include "mouse_core.h"
#include <string.h>

#define Q14 16384
#define MICRO 1000000
#define EL(t, x) ((uint16_t)((uint16_t)(t) - (x)))   /* elapsed ms, 16-bit */

static int32_t iabs(int32_t v) { return v < 0 ? -v : v; }

static uint32_t isqrt64(uint64_t v)
{
    uint64_t r = 0, b = (uint64_t)1 << 62;
    while (b > v) b >>= 2;
    while (b) {
        if (v >= r + b) { v -= r + b; r = (r >> 1) + b; } else r >>= 1;
        b >>= 2;
    }
    return (uint32_t)r;
}

/* normalize vector to Q14 unit; returns false if too short */
static bool unit_q14(const int32_t v[3], int32_t out[3])
{
    uint64_t m2 = (uint64_t)((int64_t)v[0]*v[0] + (int64_t)v[1]*v[1] + (int64_t)v[2]*v[2]);
    uint32_t m = isqrt64(m2);
    if (m < 8) return false;
    for (int i = 0; i < 3; i++) out[i] = (int32_t)(((int64_t)v[i] * Q14) / m);
    return true;
}

static void cross(const int32_t a[3], const int32_t b[3], int64_t o[3])
{
    o[0] = (int64_t)a[1]*b[2] - (int64_t)a[2]*b[1];
    o[1] = (int64_t)a[2]*b[0] - (int64_t)a[0]*b[2];
    o[2] = (int64_t)a[0]*b[1] - (int64_t)a[1]*b[0];
}

static int64_t dot_q14(const int64_t v[3], const int16_t u[3])
{
    return (v[0]*u[0] + v[1]*u[1] + v[2]*u[2]) / Q14;
}

void mc_default_cfg(mc_cfg *c)
{
    memset(c, 0, sizeof *c);
    c->finger_axis = 1; c->finger_sign = 1; c->sign_x = 1; c->sign_y = 1;
    c->rot_gain = 1500;        /* ~26 px per degree                         */
    c->rot_dead_mrad_s = 0;    /* a deadzone breaks out-and-back cancellation */
    c->accel_pct = 0;          /* >0 skews out-and-back motions             */
    c->trans_gain = 3000;      /* 3 px per mm                               */
    c->trans_dead_mg = 40;
    c->trans_leak_pct = 1;
    c->still_mg = 35; c->still_ms = 80;
    c->fast_shift = 3; c->slow_shift = 5;
    c->hold_ms = 180; c->settle_ms = 60;
    c->tap_max_ms = 250; c->dbl_gap_ms = 350;
    c->report_ms = 15;
    c->drag_enable = true;
}

void mc_init(mc_state *s, const mc_cfg *c)
{
    memset(s, 0, sizeof *s);
#ifdef MC_HOST
    s->cfg = c;
#else
    (void)c;
#endif
    memset(MC_TRANS(s), 0, sizeof(mc_trans));
}

static void stop_motion(mc_state *s)
{
    mc_trans *tr = MC_TRANS(s);
    tr->vel[0] = tr->vel[1] = 0;
    s->acc_x = s->acc_y = 0;
}

void mc_set_enabled(mc_state *s, bool on)
{
    s->enabled = on;
    s->phase = MC_IDLE;
    s->drag = false;
    s->prev_was_tap = false;
    s->click_stage = 0;
    stop_motion(s);
}

static void start_steer(mc_state *s, uint32_t t)
{
    const mc_cfg *c = MC_CFG(s);
    mc_trans *tr = MC_TRANS(s);
    int32_t g[3], up[3], f[3] = {0, 0, 0}, sd[3];
    int64_t cr[3];
    for (int i = 0; i < 3; i++) g[i] = s->gf[i] >> 3;
    s->phase = MC_STEER;
    s->t_steer = (uint16_t)t;
    tr->t_still = (uint16_t)t;
    stop_motion(s);
    for (int i = 0; i < 3; i++) tr->gs[i] = (int16_t)g[i];
    f[c->finger_axis] = c->finger_sign >= 0 ? Q14 : -Q14;
    if (!unit_q14(g, up)) { up[0] = 0; up[1] = 0; up[2] = Q14; }
    cross(f, up, cr);                  /* side = finger x up (horizontal)     */
    int32_t ci[3] = { (int32_t)(cr[0] >> 14), (int32_t)(cr[1] >> 14), (int32_t)(cr[2] >> 14) };
    if (!unit_q14(ci, sd)) {           /* finger pointing straight up/down    */
        sd[0] = sd[1] = sd[2] = 0;
        sd[(c->finger_axis + 1) % 3] = Q14;
    }
    for (int i = 0; i < 3; i++) {
        s->side[i] = (int16_t)sd[i]; s->up[i] = (int16_t)up[i];
        s->gprev[i] = (int16_t)g[i];
    }
}

void mc_touch(mc_state *s, bool pressed, uint32_t t)
{
    if (!s->enabled) return;
    const mc_cfg *c = MC_CFG(s);
    if (pressed) {
        if (s->phase != MC_IDLE) return;
        s->phase = MC_PRESSED;
        s->second_press = s->prev_was_tap && EL(t, s->t_release) <= c->dbl_gap_ms;
        s->t_press = (uint16_t)t;
        return;
    }
    /* release */
    if (s->phase == MC_PRESSED) {
        if (EL(t, s->t_press) <= c->tap_max_ms) {
            if (s->second_press) {
                s->click_stage = 1;        /* double tap -> click            */
                s->prev_was_tap = false;
            } else {
                s->prev_was_tap = true;
            }
        } else {
            s->prev_was_tap = false;
        }
    } else if (s->phase == MC_STEER) {
        s->prev_was_tap = false;
    }
    s->phase = MC_IDLE;
    s->drag = false;
    s->t_release = (uint16_t)t;
    stop_motion(s);
}

static void tick_touch(mc_state *s, uint32_t t)
{
    const mc_cfg *c = MC_CFG(s);
    if (s->phase == MC_PRESSED && EL(t, s->t_press) >= c->hold_ms) {
        s->drag = c->drag_enable && s->second_press;
        s->prev_was_tap = false;
        start_steer(s, t);
    }
}

void mc_accel(mc_state *s, mc_vec a, uint32_t t)
{
    const mc_cfg *c = MC_CFG(s);
    mc_trans *tr = MC_TRANS(s);
    int32_t raw[3] = { a.x, a.y, a.z };
    uint16_t dt = s->have_g ? EL(t, s->t_last_acc) : 0;
    s->t_last_acc = (uint16_t)t;

    if (!s->have_g) {
        for (int i = 0; i < 3; i++) s->gf[i] = (int16_t)(raw[i] << 3);
        s->have_g = true;
    } else {
        for (int i = 0; i < 3; i++)
            s->gf[i] = (int16_t)(s->gf[i] + (((raw[i] << 3) - s->gf[i]) >> c->fast_shift));
    }
    if (!s->enabled) return;
    /* hold->steer transition happens in mc_poll (keeps this frame shallow) */
    if (s->phase != MC_STEER || dt == 0 || dt > 200) return;

    int32_t g[3];
    for (int i = 0; i < 3; i++) g[i] = s->gf[i] >> 3;
    if (EL(t, s->t_steer) < c->settle_ms) {
        for (int i = 0; i < 3; i++) s->gprev[i] = (int16_t)g[i];
        return;
    }

    int64_t px = 0, py = 0;                       /* micro-pixels this sample */

    /* --- rotation: omega = gprev x g / |g|^2  (radians) ------------------- */
    if (c->rot_gain) {
        int64_t cr[3];
        int32_t gp[3] = { s->gprev[0], s->gprev[1], s->gprev[2] };
        int16_t fw[3] = { 0, 0, 0 };
        fw[c->finger_axis] = c->finger_sign >= 0 ? Q14 : -Q14;
        cross(gp, g, cr);
        int64_t g2 = (int64_t)g[0]*g[0] + (int64_t)g[1]*g[1] + (int64_t)g[2]*g[2];
        if (g2 > 10000) {
            int64_t w[3];
            /* gravity turns opposite to the hand: negate to get hand rotation */
            for (int i = 0; i < 3; i++) w[i] = -cr[i] * MICRO / g2;  /* urad */
            int64_t r[2] = { dot_q14(w, fw), dot_q14(w, s->side) };   /* twist->x, nod->y */
            int64_t dz = (int64_t)c->rot_dead_mrad_s * dt;            /* urad */
            for (int k = 0; k < 2; k++) {
                int64_t m = r[k] < 0 ? -r[k] : r[k];
                m = m > dz ? m - dz : 0;
                int64_t speed_mrad_s = m / dt;            /* urad/ms = mrad/s */
                int64_t gain = (int64_t)c->rot_gain * (100000 + speed_mrad_s * c->accel_pct) / 100000;
                int64_t v = m * gain;                     /* urad*px/rad = upx */
                r[k] = r[k] < 0 ? -v : v;
            }
            px += r[0];
            py += r[1];
        }
    }

    /* --- translation: integrate linear accel once to velocity ------------- */
    if (c->trans_gain) {
        int64_t l64[3];
        for (int i = 0; i < 3; i++) l64[i] = raw[i] - tr->gs[i];
        int32_t ax[2] = { (int32_t)dot_q14(l64, s->side), (int32_t)dot_q14(l64, s->up) };
        /* stillness = no fast change (jerk), not "equals the baseline":
         * a held tilt is still too, and must re-baseline instead of
         * integrating forever */
        bool quiet = true;
        for (int i = 0; i < 3; i++)
            if (iabs(raw[i] - g[i]) >= c->still_mg) quiet = false;
        if (!quiet) tr->t_still = (uint16_t)t;
        bool still = EL(t, tr->t_still) >= c->still_ms;
        for (int k = 0; k < 2; k++) {
            int32_t acc = ax[k];
            int32_t mag = iabs(acc) > c->trans_dead_mg ? iabs(acc) - c->trans_dead_mg : 0;
            acc = acc < 0 ? -mag : mag;
            /* um/s += mg * 9.81 m/s^2 / 1000 * dt_ms / 1000 * 1e6 */
            tr->vel[k] += acc * 981 * (int32_t)dt / 100;
            tr->vel[k] -= tr->vel[k] * c->trans_leak_pct / 100;
            if (still) tr->vel[k] = 0;
        }
        if (still)
            for (int i = 0; i < 3; i++) tr->gs[i] = (int16_t)g[i];
        /* upx = um/s * dt_ms/1000 * px/m / 1e6 * 1e6 */
        px += (int64_t)tr->vel[0] * dt * c->trans_gain / 1000;
        py += (int64_t)tr->vel[1] * dt * c->trans_gain / 1000;
    }

    for (int i = 0; i < 3; i++) s->gprev[i] = (int16_t)g[i];
    int64_t nx = s->acc_x + px * c->sign_x;
    int64_t ny = s->acc_y - py * c->sign_y;        /* screen y grows down     */
    const int64_t lim = (int64_t)2000 * MICRO;    /* fits int32 micro-px */
    if (nx > lim) nx = lim;
    if (nx < -lim) nx = -lim;
    if (ny > lim) ny = lim;
    if (ny < -lim) ny = -lim;
    s->acc_x = (int32_t)nx;
    s->acc_y = (int32_t)ny;
}

bool mc_poll(mc_state *s, uint32_t t, mc_report *out)
{
    if (!s->enabled) return false;
    tick_touch(s, t);
    out->dx = out->dy = 0;

    if (s->click_stage == 1) {
        out->buttons = 1; s->click_stage = 2; s->buttons_sent = 1;
        s->t_last_report = (uint16_t)t; return true;
    }
    if (s->click_stage == 2) {
        out->buttons = 0; s->click_stage = 0; s->buttons_sent = 0;
        s->t_last_report = (uint16_t)t; return true;
    }

    uint8_t want = s->drag ? 1 : 0;
    int32_t wx = s->acc_x / MICRO, wy = s->acc_y / MICRO;
    bool move = (wx || wy) && EL(t, s->t_last_report) >= MC_CFG(s)->report_ms;
    if (!move && want == s->buttons_sent) return false;

    if (move) {
        s->acc_x -= wx * MICRO;
        s->acc_y -= wy * MICRO;
        out->dx = (int16_t)wx;
        out->dy = (int16_t)wy;
    }
    out->buttons = want;
    s->buttons_sent = want;
    s->t_last_report = (uint16_t)t;
    return true;
}
