package com.gadscape.api.service.item

import com.gadscape.api.GameDatabase
import com.gadscape.api.model.ItemConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object ItemConfigManager {

    private val logger = LoggerFactory.getLogger(ItemConfigManager::class.java)
    private val itemConfigs = ConcurrentHashMap<Int, ItemConfig>()

    fun refresh() {
        logger.info("Refreshing item configs...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val newItemConfigs = GameDatabase.retrieveItemConfigs()
                logger.info("Retrieved ${newItemConfigs.size} item configs from the database.")
                itemConfigs.clear()
                newItemConfigs.forEach { itemConfigs[it.id] = it }
            } catch (e: Exception) {
                logger.error("Failed to refresh item configs, keeping old ones.", e)
            }
        }
    }

    operator fun get(itemId: Int) = itemConfigs[itemId]?: error("Item config not found for item id: $itemId")
}
