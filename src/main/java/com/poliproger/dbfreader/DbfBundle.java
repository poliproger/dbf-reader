package com.poliproger.dbfreader;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.PropertyKey;

/**
 * Message bundle for all user-facing strings of the DBF Reader plugin.
 */
public final class DbfBundle {

    @NonNls
    private static final String BUNDLE = "messages.DbfBundle";

    private static final DynamicBundle INSTANCE = new DynamicBundle(DbfBundle.class, BUNDLE);

    private DbfBundle() {
    }

    public static @Nls String message(@PropertyKey(resourceBundle = BUNDLE) String key, Object... params) {
        return INSTANCE.getMessage(key, params);
    }
}
