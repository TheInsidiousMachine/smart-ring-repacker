/* Host unit tests for mouse_core with a synthetic hand/ring simulator.
 * Sensor frame used by the sim: +Y along the finger, +Z out of the back of
 * the hand. Palm down, finger forward => accel reads (0, 0, +1000) mg.     */
#include "mouse_core.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

static int fails;
#define CHECK(cond, ...) do { if (!(cond)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

typedef struct {
    mc_state s;
    uint32_t t;
    double pitch, roll;           /* rad: nod (finger up +), twist about finger */
    double lin_side, lin_up;      /* external linear accel, mg                  */
    double noise;                 /* mg, uniform +-                             */
    long   x, y;                  /* integrated cursor                          */
    int    clicks_down, clicks_up, reports_after_release, drag_reports;
    int    btn;
} sim;

static void sim_init(sim *m, const mc_cfg *c)
{
    *m = (sim){0};
    mc_init(&m->s, c);
    mc_set_enabled(&m->s, true);
    srand(1);
}

static mc_vec sample(sim *m)
{
    /* gravity reaction (+1 g "up") in world, rotate into sensor frame.
     * Pitch about sensor X (side axis), roll about sensor Y (finger).      */
    double gx = 0, gy = 0, gz = 1000.0;
    gx += m->lin_side; gz += m->lin_up;
    /* world -> sensor: inverse of R = Ry(roll) * Rx(pitch) */
    double cp = cos(m->pitch), sp = sin(m->pitch), cr = cos(m->roll), sr = sin(m->roll);
    /* apply Ry(-roll) then Rx(-pitch) */
    double x1 = cr*gx - sr*gz, z1 = sr*gx + cr*gz, y1 = gy;
    double y2 = cp*y1 + sp*z1, z2 = -sp*y1 + cp*z1;
    double n = m->noise;
    mc_vec v = {
        (int16_t)lround(x1 + (n ? ((rand() / (double)RAND_MAX) * 2 - 1) * n : 0)),
        (int16_t)lround(y2 + (n ? ((rand() / (double)RAND_MAX) * 2 - 1) * n : 0)),
        (int16_t)lround(z2 + (n ? ((rand() / (double)RAND_MAX) * 2 - 1) * n : 0)),
    };
    return v;
}

/* advance 10 ms: one accel sample at 100 Hz, polls every 5 ms */
static void step(sim *m)
{
    for (int k = 0; k < 2; k++) {
        m->t += 5;
        if (k == 1) mc_accel(&m->s, sample(m), m->t);
        mc_report r;
        while (mc_poll(&m->s, m->t, &r)) {
            m->x += r.dx; m->y += r.dy;
            if (r.buttons && !m->btn) m->clicks_down++;
            if (!r.buttons && m->btn) m->clicks_up++;
            if (r.buttons && (r.dx || r.dy)) m->drag_reports++;
            if (m->s.phase == MC_IDLE && (r.dx || r.dy)) m->reports_after_release++;
            m->btn = r.buttons;
        }
    }
}
static void run(sim *m, int ms) { for (int i = 0; i < ms / 10; i++) step(m); }
static void touch(sim *m, bool p) { mc_touch(&m->s, p, m->t); }

static void t_still_no_drift(const mc_cfg *c)
{
    sim m; sim_init(&m, c); m.noise = 15;
    run(&m, 200); touch(&m, true); run(&m, 10000); touch(&m, false);
    CHECK(labs(m.x) <= 3 && labs(m.y) <= 3, "still drift x=%ld y=%ld", m.x, m.y);
}

static void t_no_touch_no_motion(const mc_cfg *c)
{
    sim m; sim_init(&m, c);
    run(&m, 200);
    for (int i = 0; i < 50; i++) { m.pitch += 0.01; m.roll += 0.01; step(&m); }
    CHECK(m.x == 0 && m.y == 0, "moved without touch x=%ld y=%ld", m.x, m.y);
}

static void t_pitch_moves_y(const mc_cfg *c)
{
    sim m; sim_init(&m, c);
    run(&m, 200); touch(&m, true); run(&m, 300);
    for (int i = 0; i < 50; i++) { m.pitch += (20.0 * M_PI / 180) / 50; step(&m); }  /* nod up 20 deg */
    run(&m, 300);
    CHECK(m.y < -150, "nod up should move cursor up, y=%ld", m.y);
    CHECK(labs(m.x) < labs(m.y) / 5, "nod leaked into x=%ld (y=%ld)", m.x, m.y);
}

static void t_roll_moves_x(const mc_cfg *c)
{
    sim m; sim_init(&m, c);
    run(&m, 200); touch(&m, true); run(&m, 300);
    for (int i = 0; i < 50; i++) { m.roll += (20.0 * M_PI / 180) / 50; step(&m); }
    run(&m, 300);
    CHECK(labs(m.x) > 150, "twist should move x, x=%ld", m.x);
    CHECK(labs(m.y) < labs(m.x) / 5, "twist leaked into y=%ld (x=%ld)", m.y, m.x);
}

static void t_release_stops(const mc_cfg *c)
{
    sim m; sim_init(&m, c);
    run(&m, 200); touch(&m, true); run(&m, 300);
    for (int i = 0; i < 20; i++) { m.pitch += 0.01; step(&m); }
    touch(&m, false);
    long y0 = m.y;
    for (int i = 0; i < 30; i++) { m.pitch += 0.01; step(&m); }
    CHECK(m.y == y0, "moved after release (%ld -> %ld)", y0, m.y);
}

static void t_double_tap_click(const mc_cfg *c)
{
    sim m; sim_init(&m, c);
    run(&m, 200);
    touch(&m, true); run(&m, 80); touch(&m, false);
    run(&m, 150);
    touch(&m, true); run(&m, 80); touch(&m, false);
    run(&m, 200);
    CHECK(m.clicks_down == 1 && m.clicks_up == 1, "click down=%d up=%d", m.clicks_down, m.clicks_up);
    CHECK(m.x == 0 && m.y == 0, "click moved cursor");
}

static void t_single_and_slow_taps_no_click(const mc_cfg *c)
{
    sim m; sim_init(&m, c);
    run(&m, 200);
    touch(&m, true); run(&m, 80); touch(&m, false);
    run(&m, 600);                                    /* gap too long */
    touch(&m, true); run(&m, 80); touch(&m, false);
    run(&m, 600);
    CHECK(m.clicks_down == 0, "unexpected click %d", m.clicks_down);
}

static void t_tap_hold_drags(const mc_cfg *c)
{
    sim m; sim_init(&m, c);
    run(&m, 200);
    touch(&m, true); run(&m, 80); touch(&m, false);
    run(&m, 100);
    touch(&m, true); run(&m, 300);
    for (int i = 0; i < 40; i++) { m.pitch += 0.008; step(&m); }
    touch(&m, false); run(&m, 100);
    CHECK(m.drag_reports > 0, "no motion with button held");
    CHECK(m.btn == 0 && m.clicks_down == 1 && m.clicks_up == 1, "drag press/release wrong d=%d u=%d btn=%d",
          m.clicks_down, m.clicks_up, m.btn);
}

static void t_sideways_stroke(const mc_cfg *c)
{
    sim m; sim_init(&m, c);
    run(&m, 300); touch(&m, true); run(&m, 300);
    /* ~0.3 m/s peak: +200 mg for 150 ms then -200 mg for 150 ms */
    m.lin_side = 200; run(&m, 150);
    m.lin_side = -200; run(&m, 150);
    m.lin_side = 0; run(&m, 500);
    long x_end = m.x; run(&m, 1000);
    CHECK(m.x > 40, "rightward stroke should move right, x=%ld", m.x);
    CHECK(labs(m.x - x_end) <= 2, "drift after stroke %ld -> %ld", x_end, m.x);
    printf("  sideways stroke: x=%ld y=%ld\n", m.x, m.y);
}

static void t_disabled(const mc_cfg *c)
{
    sim m; sim_init(&m, c); mc_set_enabled(&m.s, false);
    run(&m, 200); touch(&m, true); run(&m, 300);
    for (int i = 0; i < 50; i++) { m.pitch += 0.01; step(&m); }
    touch(&m, false); run(&m, 80);
    touch(&m, true); run(&m, 80); touch(&m, false); run(&m, 100);
    CHECK(m.x == 0 && m.y == 0 && m.clicks_down == 0, "disabled but emitted");
}

int main(void)
{
    mc_cfg c; mc_default_cfg(&c);
    t_still_no_drift(&c);
    t_no_touch_no_motion(&c);
    t_pitch_moves_y(&c);
    t_roll_moves_x(&c);
    t_release_stops(&c);
    t_double_tap_click(&c);
    t_single_and_slow_taps_no_click(&c);
    t_tap_hold_drags(&c);
    t_sideways_stroke(&c);
    t_disabled(&c);
    printf(fails ? "%d FAILED\n" : "all mouse_core tests passed\n", fails);
    return fails != 0;
}
