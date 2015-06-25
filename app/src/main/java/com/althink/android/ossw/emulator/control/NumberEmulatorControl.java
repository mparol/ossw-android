package com.althink.android.ossw.emulator.control;

import com.althink.android.ossw.emulator.renderer.LowLevelRenderer;
import com.althink.android.ossw.emulator.source.EmulatorDataSource;

/**
 * Created by krzysiek on 14/06/15.
 */
public class NumberEmulatorControl extends AbstractEmulatorControl {
    private NumberFormat format;
    private int x;
    private int y;
    private int width;
    private int height;
    private int thickness;

    public NumberEmulatorControl(NumberFormat format, int x, int y, int width, int height, int thickness, EmulatorDataSource dataSource) {
        super(dataSource);
        this.format = format;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.thickness = thickness;
    }

    public void draw(LowLevelRenderer renderer) {
        Integer value = (Integer) getData();
        if (value == null) {
            return;
        }
        switch (format) {
            case NUMBER_FORMAT_0__99: {
                if (value > 99) {
                    value = 99;
                }

                int digit_dist = (width / 32) + 1;
                int digit_width = (width - digit_dist) / 2;

                renderer.drawDigit(value / 10, x, y, digit_width, height, thickness);
                renderer.drawDigit(value % 10, x + digit_dist + digit_width, y, digit_width, height, thickness);
            }
            break;
        }
    }

    public static enum NumberFormat {
        NUMBER_FORMAT_0__9(0x10),
        NUMBER_FORMAT_0__19(0x20),
        NUMBER_FORMAT_0__99(0x30),
        NUMBER_FORMAT_0__199(0x40),
        NUMBER_FORMAT_0__999(0x50),
        NUMBER_FORMAT_0__1999(0x60),
        NUMBER_FORMAT_0__9999(0x70),
        NUMBER_FORMAT_0__19999(0x80),
        NUMBER_FORMAT_0__99999(0x90);

        private int key;

        private NumberFormat(int key) {
            this.key = key;
        }

        public static NumberFormat resolveByKey(int key) {
            for (NumberFormat format : NumberFormat.values()) {
                if (format.key == key) {
                    return format;
                }
            }
            return null;
        }
    }
}