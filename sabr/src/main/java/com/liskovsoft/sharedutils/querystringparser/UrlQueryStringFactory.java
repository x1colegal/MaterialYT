package com.liskovsoft.sharedutils.querystringparser;

import android.net.Uri;
import java.util.LinkedHashMap;
import java.util.Map;

public final class UrlQueryStringFactory {
    private UrlQueryStringFactory() { }
    public static UrlQueryString parse(final String url) {
        final Map<String, String> values = new LinkedHashMap<>();
        if (url != null) {
            final Uri uri = Uri.parse(url);
            for (final String name : uri.getQueryParameterNames()) values.put(name, uri.getQueryParameter(name));
        }
        return new UrlQueryString(values);
    }
}
