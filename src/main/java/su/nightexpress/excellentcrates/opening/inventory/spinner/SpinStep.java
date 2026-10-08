package su.nightexpress.excellentcrates.opening.inventory.spinner;

import org.jetbrains.annotations.NotNull;
import su.nightexpress.nightcore.util.Lists;
import su.nightexpress.nightcore.util.NumberUtil;

import java.util.ArrayList;
import java.util.List;

public class SpinStep {

    private static final String DELIMITER = ":";

    private final int spinsAmount;
    private final int tickInterval;
    private final int burst; // CatCraft: spins per spin time (e.g. 2 = two slots per tick)

    public SpinStep(int spinsAmount, int tickInterval) {
        this(spinsAmount, tickInterval, 1);
    }

    public SpinStep(int spinsAmount, int tickInterval, int burst) {
        this.spinsAmount = spinsAmount;
        this.tickInterval = tickInterval;
        this.burst = Math.max(1, burst);
    }

    @NotNull
    public static SpinStep of(int spinsAmount, int tickInterval) {
        return new SpinStep(spinsAmount, tickInterval);
    }

    @NotNull
    public static SpinStep deserialize(@NotNull String str) {
        String[] split = str.split(DELIMITER);
        int amount = NumberUtil.getIntegerAbs(split[0]);
        int tickInterval = split.length >= 2 ? NumberUtil.getIntegerAbs(split[1]) : 0;
        int burst = split.length >= 3 ? NumberUtil.getIntegerAbs(split[2]) : 1; // CatCraft: "amount:interval:burst"

        return new SpinStep(amount, tickInterval, burst);
    }

    @NotNull
    public String serialize() {
        return this.spinsAmount + DELIMITER + this.tickInterval + (this.burst > 1 ? DELIMITER + this.burst : "");
    }

    @NotNull
    public static List<SpinStep> convertFromFlat(int totalSpins, int initialSpeed, int slowdownInterval, int slowdownStrength) {
        if (slowdownInterval <= 0 || slowdownStrength <= 0) {
            return Lists.newList(new SpinStep(totalSpins, initialSpeed));
        }

        List<SpinStep> segments = new ArrayList<>();
        int remainingSpins = totalSpins;
        int index = 0;

        while (remainingSpins > 0) {
            int spinsThisStep = Math.min(slowdownInterval, remainingSpins);
            int speed = initialSpeed + (index * slowdownStrength);
            segments.add(new SpinStep(spinsThisStep, speed));

            remainingSpins -= spinsThisStep;
            index++;
        }

        return segments;
    }

    public int getSpinsAmount() {
        return this.spinsAmount;
    }

    public int getBurst() {
        return this.burst;
    }

    public int getTickInterval() {
        return this.tickInterval;
    }

    @Override
    public String toString() {
        return "SpinSpeed{" +
            "amount=" + spinsAmount +
            ", speed=" + tickInterval +
            '}';
    }
}
