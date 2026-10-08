package su.nightexpress.excellentcrates.opening.inventory.spinner;

public enum SpinMode {
    INDEPENDENT,
    SEQUENTAL,
    SYNCRHONIZED,
    RANDOM,
    /** CatCraft: fills every slot on the first spin, then rotates them (the last slot wraps to the first), like a wheel. */
    LOOP
}
