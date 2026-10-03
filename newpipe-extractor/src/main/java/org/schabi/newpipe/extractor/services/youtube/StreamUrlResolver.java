// SPDX-FileCopyrightText: 2026 MaterialYT contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package org.schabi.newpipe.extractor.services.youtube;

import javax.annotation.Nonnull;

/** Resolves YouTube streaming URL challenges before a stream is exposed to the player. */
@FunctionalInterface
public interface StreamUrlResolver {
    @Nonnull
    String resolve(@Nonnull String videoId, @Nonnull String streamingUrl) throws Exception;
}
