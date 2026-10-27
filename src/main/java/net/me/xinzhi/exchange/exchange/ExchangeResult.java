package net.me.xinzhi.exchange.exchange;

/** 一次兑换的结果（成功与否 + 给玩家看的说明）。 */
public final class ExchangeResult {

    public final boolean ok;
    public final String message;
    public final long amount;
    public final double unit;
    public final double total;

    private ExchangeResult(boolean ok, String message, long amount, double unit, double total) {
        this.ok = ok;
        this.message = message;
        this.amount = amount;
        this.unit = unit;
        this.total = total;
    }

    public static ExchangeResult ok(long amount, double unit, double total) {
        return new ExchangeResult(true, null, amount, unit, total);
    }

    public static ExchangeResult fail(String message) {
        return new ExchangeResult(false, message, 0, 0, 0);
    }
}
