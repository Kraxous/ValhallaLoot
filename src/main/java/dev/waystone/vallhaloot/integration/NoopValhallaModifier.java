package dev.waystone.vallhaloot.integration;

import dev.waystone.vallhaloot.loot.LootModifier;
import dev.waystone.vallhaloot.loot.LootRollResult;

/**
 * No-op implementation of LootModifier for when ValhallaMMO is not available.
 */
public class NoopValhallaModifier implements LootModifier {
    @Override
    public void modify(LootRollResult result) {
        // Do nothing
    }
}
