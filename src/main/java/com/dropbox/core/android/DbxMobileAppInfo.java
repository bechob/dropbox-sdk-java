package com.dropbox.core.android;

import com.dropbox.core.DbxAppInfo;
import com.dropbox.core.DbxHost;
import com.dropbox.core.util.DumpWriter;

public class DbxMobileAppInfo extends DbxAppInfo {

    public DbxMobileAppInfo(String key) {
        super(key, null);
    }

    @Override
    public void checkSecretArg(String secret)
    {
        return;
    }
}
