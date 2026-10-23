package cn.workbuddy.r1client;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-local SIP account store. No private domain or password is bundled. */
public final class SipAccountStore {
    private static final String PREFS = "r1_sip_account";
    private final SharedPreferences prefs;

    public SipAccountStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context required");
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return prefs.getBoolean("enabled", false); }

    public void setEnabled(boolean enabled) {
        if (enabled && load() == null)
            throw new IllegalStateException("Configure SIP credentials first");
        prefs.edit().putBoolean("enabled", enabled).apply();
    }

    public SipAccountConfig load() {
        String host = prefs.getString("host", "");
        String password = prefs.getString("password", "");
        if (host == null || host.length() == 0 || password == null || password.length() == 0)
            return null;
        try {
            return new SipAccountConfig(host, prefs.getInt("port", 5060),
                    prefs.getString("extension", ""),
                    prefs.getString("auth_user", ""),
                    password, prefs.getInt("expires", 300));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public void save(SipAccountConfig account) {
        if (account == null) throw new IllegalArgumentException("Account required");
        prefs.edit()
                .putString("host", account.host)
                .putInt("port", account.port)
                .putString("extension", account.extension)
                .putString("auth_user", account.authUser)
                .putString("password", account.password)
                .putInt("expires", account.registerExpires)
                .apply();
    }

    public void disableAndForget() {
        prefs.edit().putBoolean("enabled", false).remove("password").apply();
    }

    /** Suitable for status endpoints; never exposes the domain or credentials. */
    public String safeSummary() {
        return isEnabled() ? "enabled" : (load() == null ? "not configured" : "disabled");
    }
}
