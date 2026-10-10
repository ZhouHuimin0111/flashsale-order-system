package io.github.zhouhuimin.flashsale.coupon;

import java.time.Instant;

public record Coupon(long id, String title, int priceCents, int initialStock, int stock, int version, Instant startsAt, Instant endsAt) {}
