    .syntax unified
    .thumb
    .section .stub, "ax"
    .global spo2_stub
    .thumb_func
spo2_stub:              @ old SpO2 sample entry (0x10026404): do nothing, return 0
    movs r0, #0
    bx lr
    .align 2
