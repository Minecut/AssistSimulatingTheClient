package com.dmod.eagle.gui;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * One editable value on a module row.
 *
 * <p>Two kinds, matching what the config actually holds: a boolean rendered as a small checkbox, and a
 * number rendered as a draggable track. Both read and write straight through to the {@link
 * com.dmod.eagle.EagleConfig} fields, so the GUI never keeps its own copy of anything.
 */
public abstract class GuiSetting {

    /** Label shown on the left of the row. */
    public final String name;

    GuiSetting(String name) {
        this.name = name;
    }

    /** The value text shown on the right, or {@code null} when the row has none. */
    public abstract String valueText();

    /** Fill fraction of the row, 0..1. */
    public abstract float ratio();

    /** True for checkbox rows, which draw an indicator instead of a track. */
    public abstract boolean checkbox();

    /** Flip a checkbox. No-op for numbers. */
    public void toggle() {
    }

    /** Set from a drag. {@code t} is 0..1 across the row. No-op for booleans. */
    public void setFromRatio(float t) {
    }

    // ------------------------------------------------------------------ kinds

    public static final class Bool extends GuiSetting {

        private final BooleanSupplier getter;
        private final Consumer<Boolean> setter;

        public Bool(String name, BooleanSupplier getter, Consumer<Boolean> setter) {
            super(name);
            this.getter = getter;
            this.setter = setter;
        }

        public boolean value() {
            return this.getter.getAsBoolean();
        }

        @Override
        public void toggle() {
            this.setter.accept(!value());
        }

        @Override
        public String valueText() {
            return null;
        }

        @Override
        public float ratio() {
            return value() ? 1.0F : 0.0F;
        }

        @Override
        public boolean checkbox() {
            return true;
        }
    }

    public static final class Number extends GuiSetting {

        private final double min;
        private final double max;
        private final int decimals;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        public Number(String name, double min, double max, int decimals,
                      DoubleSupplier getter, DoubleConsumer setter) {
            super(name);
            this.min = min;
            this.max = max;
            this.decimals = decimals;
            this.getter = getter;
            this.setter = setter;
        }

        public double value() {
            return this.getter.getAsDouble();
        }

        @Override
        public String valueText() {
            return this.decimals <= 0
                    ? Integer.toString((int) Math.round(value()))
                    : String.format(Locale.ROOT, "%." + this.decimals + "f", value());
        }

        @Override
        public float ratio() {
            double span = this.max - this.min;

            if (span <= 0.0D) {
                return 0.0F;
            }

            return (float) Math.max(0.0D, Math.min(1.0D, (value() - this.min) / span));
        }

        @Override
        public void setFromRatio(float t) {
            float clamped = Math.max(0.0F, Math.min(1.0F, t));
            double target = this.min + (this.max - this.min) * clamped;

            // Snap to the displayed precision so what you see is what gets written to the config.
            double step = this.decimals <= 0 ? 1.0D : Math.pow(10.0D, -this.decimals);
            target = Math.round(target / step) * step;
            target = Math.max(this.min, Math.min(this.max, target));

            this.setter.accept(target);
        }

        @Override
        public boolean checkbox() {
            return false;
        }
    }
}
