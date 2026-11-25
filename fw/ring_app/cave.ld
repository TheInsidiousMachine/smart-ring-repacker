/* Freed SpO2 algorithm region in V2.1.4 */
MEMORY { CAVE (rx) : ORIGIN = 0x10026404, LENGTH = 0x1002831c - 0x10026404 }
SECTIONS {
  .text : {
    KEEP(*(.stub))
    *(.text .text.*)
    *(.rodata .rodata.*)
  } > CAVE
  .data : { *(.data .data.*) } > CAVE
  .bss  : { *(.bss .bss.* COMMON) } > CAVE
  /DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes) *(.comment) }
}
ASSERT(SIZEOF(.data) == 0, "no initialized RAM allowed")
ASSERT(SIZEOF(.bss) == 0, "no zero-init RAM allowed")
