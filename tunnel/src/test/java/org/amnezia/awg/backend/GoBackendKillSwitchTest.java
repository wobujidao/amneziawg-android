/*
 * Copyright © 2026 Mayak VPN. SPDX-License-Identifier: Apache-2.0
 *
 * Юнит-тест per-family kill-switch (Маяк, SPEC-0014): проверяем, что при v4-only конфиге IPv6
 * ГЛУШИТСЯ (не течёт мимо VPN) — фикс IPv6-утечки после вопроса владельца «а если у меня IPv6?».
 */
package org.amnezia.awg.backend;

import static org.junit.Assert.assertArrayEquals;

import org.amnezia.awg.config.InetNetwork;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class GoBackendKillSwitchTest {

    private static List<InetNetwork> ips(final String... nets) {
        final List<InetNetwork> out = new ArrayList<>();
        try {
            for (final String n : nets) out.add(InetNetwork.parse(n));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // {blockV4, blockV6}
    @Test
    public void fullTunnelDualStack_blocksNeither() {
        assertArrayEquals(new boolean[]{false, false}, GoBackend.killSwitchBlock(ips("0.0.0.0/0", "::/0"), 1));
    }

    @Test
    public void v4OnlyFullTunnel_blocksIpv6_noLeak() {
        // ГЛАВНЫЙ КЕЙС: 0.0.0.0/0 без ::/0 → IPv6 надо заглушить, иначе нативный IPv6 телефона утечёт.
        assertArrayEquals(new boolean[]{false, true}, GoBackend.killSwitchBlock(ips("0.0.0.0/0"), 1));
    }

    @Test
    public void v6OnlyFullTunnel_blocksIpv4() {
        assertArrayEquals(new boolean[]{true, false}, GoBackend.killSwitchBlock(ips("::/0"), 1));
    }

    @Test
    public void splitTunnel_blocksBoth() {
        assertArrayEquals(new boolean[]{true, true}, GoBackend.killSwitchBlock(ips("10.0.0.0/8"), 1));
    }

    @Test
    public void multiPeer_blocksBothEvenWithDefaults() {
        // Несколько пиров → нет чистого full-tunnel одним пиром → защищаемся allowFamily по обоим.
        assertArrayEquals(new boolean[]{true, true}, GoBackend.killSwitchBlock(ips("0.0.0.0/0", "::/0"), 2));
    }
}
