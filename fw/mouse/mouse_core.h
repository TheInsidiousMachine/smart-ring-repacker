/* mouse_core: accelerometer + touch -> relative HID mouse reports.
 *
 * Pure logic, no hardware access, integer math only (Cortex-M0 friendly).
 * Same file is built for the host unit tests and for the ring.
 *
 * Behavior (see docs/MOUSE_PROPOSAL.md):
 *   - mouse mode off            -> nothing is ever emitted
 *   - touch held >= hold_ms     -> "steering": hand motion moves the cursor
 *                                  relative to where it is; release stops it
 *   - two quick taps            -> one left click at the current cursor
 *   - tap, then press-and-hold  -> drag (left button held while steering)
 *
 * Motion sources while steering (sensor is accelerometer-only, no gyro):
 *   rotation    : change of the gravity direction between samples, split
 *                 into twist about the finger (-> dx) and nod up/down (-> dy)
 *   translation : linear accel (raw - gravity baseline) integrated once to a
 *                 velocity with leak + stillness reset (-> dx/dy)
 * Rotation about the vertical axis is physically invisible to an
 * accelerometer and cannot move the cursor.
 *
 * RAM layout is deliberately tight (the ring only has a few freed bytes):
 * timestamps are 16-bit ms (intervals < 65 s), gravity is int16, and the
 * translation state lives in a separate struct that the ring places at its
 * own fixed address (see MC_TRANS / MC_CFG below).
 */
#ifndef MOUSE_CORE_H
#define MOUSE_CORE_H
#include <stdint.h>
#include <stdbool.h>

typedef struct { int16_t x, y, z; } mc_vec;          /* milli-g, sensor frame */

typedef struct {
    uint8_t  finger_axis;     /* 0=x 1=y 2=z: sensor axis along the finger   */
    int8_t   finger_sign;     /* +1 / -1                                      */
    int8_t   sign_x, sign_y;  /* flip screen directions                       */
    uint16_t rot_gain;        /* pixels per radian                            */
    uint16_t rot_dead_mrad_s; /* rotation-rate deadzone                       */
    uint16_t accel_pct;       /* extra gain % per rad/s (pointer ballistics)  */
    uint16_t trans_gain;      /* pixels per metre of hand travel (0 = off)    */
    uint16_t trans_dead_mg;   /* linear-accel deadband                        */
    uint8_t  trans_leak_pct;  /* velocity leak per sample                     */
    uint16_t still_mg;        /* stillness threshold for velocity reset       */
    uint16_t still_ms;
    uint8_t  fast_shift;      /* gravity LPF: alpha = 1/2^n                   */
    uint8_t  slow_shift;      /* (unused; kept for config compatibility)      */
    uint16_t hold_ms;         /* press longer than this = steering            */
    uint16_t settle_ms;       /* ignore motion right after steering starts    */
    uint16_t tap_max_ms;      /* press shorter than this = tap                */
    uint16_t dbl_gap_ms;      /* max release->press gap inside a double tap   */
    uint16_t report_ms;       /* min interval between motion reports          */
    bool     drag_enable;
} mc_cfg;

typedef struct { uint8_t buttons; int16_t dx, dy; } mc_report;

enum { MC_IDLE, MC_PRESSED, MC_STEER };

/* translation state (16 bytes) */
typedef struct {
    int16_t  gs[3];               /* gravity baseline while steering, mg   */
    uint16_t t_still;
    int32_t  vel[2];              /* um/s                                  */
} mc_trans;

typedef struct {
#ifdef MC_HOST
    const mc_cfg *cfg;
    mc_trans trans;
#endif
    uint8_t  enabled, phase, have_g, prev_was_tap, second_press, drag;
    uint8_t  click_stage;         /* 0 none, 1 send down, 2 send up        */
    uint8_t  buttons_sent;
    int16_t  gf[3];               /* gravity LPF, mg << 3                  */
    int16_t  gprev[3];            /* previous gravity, mg                  */
    int16_t  side[3], up[3];      /* steering frame, Q14 unit vectors      */
    int32_t  acc_x, acc_y;        /* sub-pixel accumulators, micro-pixels  */
    uint16_t t_press, t_release, t_steer, t_last_acc, t_last_report;
} mc_state;

#ifdef MC_HOST
#define MC_CFG(s)   ((s)->cfg)
#define MC_TRANS(s) (&(s)->trans)
#else
/* ring build: provided by the firmware glue */
extern const mc_cfg mc_ring_cfg;
#define MC_CFG(s)   (&mc_ring_cfg)
#ifndef MC_TRANS_ADDR
#error "define MC_TRANS_ADDR for the ring build"
#endif
#define MC_TRANS(s) ((mc_trans *)(MC_TRANS_ADDR))
#endif

void mc_default_cfg(mc_cfg *c);
void mc_init(mc_state *s, const mc_cfg *c);
void mc_set_enabled(mc_state *s, bool on);
void mc_touch(mc_state *s, bool pressed, uint32_t t_ms);
void mc_accel(mc_state *s, mc_vec a, uint32_t t_ms);
/* Returns true and fills *out when a HID report should be sent now. */
bool mc_poll(mc_state *s, uint32_t t_ms, mc_report *out);

#endif
