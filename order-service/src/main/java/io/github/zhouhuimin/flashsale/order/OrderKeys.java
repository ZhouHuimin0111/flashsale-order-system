package io.github.zhouhuimin.flashsale.order;

// Hash tag keeps every key used by one reservation Lua script in the same Redis Cluster slot.
public final class OrderKeys {
    private OrderKeys() {}
    private static String base(long coupon) { return "sale:{" + coupon + "}:"; }
    public static String inventory(long coupon) { return base(coupon) + "inventory"; }
    public static String buyers(long coupon) { return base(coupon) + "buyers"; }
    public static String status(long coupon) { return base(coupon) + "status"; }
    public static String owners(long coupon) { return base(coupon) + "owners"; }
    public static String outbox(long coupon) { return base(coupon) + "outbox"; }
}
