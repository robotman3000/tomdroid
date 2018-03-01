package sbs20.filenotes.model;

import android.content.SharedPreferences;

public class Settings {

    private SharedPreferences sharedPreferences;

    public static final String DROPBOX_ACCESS_TOKEN = "pref_dbx_access_token";

    public Settings(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
    }

    public String get(String key, String dflt) {
        return this.sharedPreferences.getString(key, dflt);
    }

    public String get(String key) {
        return this.get(key, null);
    }

    public void set(String key, String value) {
        Logger.verbose(this, "set(" + key + ", " + value + ")");
        this.sharedPreferences.edit().putString(key, value).apply();
    }

    public void remove(String key) {
        Logger.verbose(this, "remove(" + key + ")");
        this.sharedPreferences.edit().remove(key).commit();
    }

    public String getDropboxAccessToken() {
        return this.get(DROPBOX_ACCESS_TOKEN, null);
    }

    public void setDropboxAccessToken(String value) {
        this.set(DROPBOX_ACCESS_TOKEN, value);
    }

    public void clearDropboxAccessToken() {
        this.remove(DROPBOX_ACCESS_TOKEN);
    }

    public String getRemoteStoragePath() {
        return "/";
    }

}
