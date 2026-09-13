public final class R1BootstrapLoggingProbe {
    private R1BootstrapLoggingProbe() {}

    public static void main(String[] arguments) throws Exception {
        Class<?> factory = Class.forName("org.slf4j.LoggerFactory");
        Object logger = factory.getMethod("getLogger", String.class).invoke(null, "org.jooq.Constants");
        Class<?> loggerType = Class.forName("org.slf4j.Logger");
        loggerType.getMethod("info", String.class).invoke(logger, "r1-bootstrap-probe-log");
        System.out.println("{\"mode\":\"PROBE\"}");
    }
}
