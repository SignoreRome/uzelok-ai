package uzelok.domain.model;

import java.math.BigDecimal;

public record TenantStats(long notes, long jobs24h, BigDecimal spent24h,
                          BigDecimal spentMonth) {
}
