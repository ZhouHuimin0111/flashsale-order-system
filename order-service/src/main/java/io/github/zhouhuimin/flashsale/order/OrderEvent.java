package io.github.zhouhuimin.flashsale.order;

public record OrderEvent(String orderId, long userId, long couponId) {}
