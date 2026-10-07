package com.liskovsoft.sharedutils.querystringparser;

import java.util.Map;

public final class UrlQueryString {
    private final Map<String, String> values;
    UrlQueryString(final Map<String, String> values) { this.values = values; }
    public String get(final String key) { return values.get(key); }
}
