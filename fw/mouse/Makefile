# Host tests:   make test
# Ring size:    make arm   (needs arm-none-eabi-gcc on PATH, or ARMGCC=/path/to/bin/arm-none-eabi-)
ARMGCC ?= arm-none-eabi-
OUT ?= build
test:
	mkdir -p $(OUT) && gcc -std=c99 -DMC_HOST -D_DEFAULT_SOURCE -Wall -Wextra -O1 -o $(OUT)/test_mouse_core mouse_core.c test_mouse_core.c -lm && $(OUT)/test_mouse_core
arm:
	mkdir -p $(OUT) && $(ARMGCC)gcc -mcpu=cortex-m0 -mthumb -Os -std=c99 -DMC_TRANS_ADDR=0x20002878 -Wall -Wextra -c mouse_core.c -o $(OUT)/mouse_core.o && $(ARMGCC)size $(OUT)/mouse_core.o
.PHONY: test arm
