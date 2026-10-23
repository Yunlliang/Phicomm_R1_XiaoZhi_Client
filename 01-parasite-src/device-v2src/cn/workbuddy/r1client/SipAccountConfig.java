package cn.workbuddy.r1client;

/** Immutable, user-entered SIP credentials; never supply real credentials in source. */
public final class SipAccountConfig {
    public final String host;
    public final int port;
    public final String extension;
    public final String authUser;
    public final String password;
    public final int registerExpires;

    public SipAccountConfig(String host, int port, String extension,
                            String authUser, String password, int registerExpires) {
        if (host == null || !host.matches("[A-Za-z0-9.:-]{1,255}"))
            throw new IllegalArgumentException("SIP server/domain required");
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid SIP port");
        if (extension == null || !extension.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("Invalid SIP extension");
        if (authUser == null || !authUser.matches("[A-Za-z0-9_.@-]{1,128}"))
            throw new IllegalArgumentException("Invalid authorization username");
        if (password == null || password.length() == 0 || password.length() > 256)
            throw new IllegalArgumentException("SIP password required");
        if (registerExpires < 60 || registerExpires > 3600)
            throw new IllegalArgumentException("Invalid registration interval");
        this.host = host;
        this.port = port;
        this.extension = extension;
        this.authUser = authUser;
        this.password = password;
        this.registerExpires = registerExpires;
    }

    @Override public String toString() {
        return "SipAccountConfig{host=[REDACTED],user=[REDACTED],password=[REDACTED]}";
    }
}
