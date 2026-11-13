package com.homehub.zwave;

/**
 * One Z-Wave node as Kudil keeps it in memory: only what the status page needs. Security keys, DSKs and the raw
 * node state are never kept or logged.
 *
 * @param id         node ID (1 is usually the stick itself)
 * @param name       the name given in Z-Wave JS, else the device's label, else "Node 5"
 * @param location   the room given in Z-Wave JS, or ""
 * @param status     alive, asleep, awake, dead or unknown
 * @param ready      whether Z-Wave JS has finished interviewing it
 * @param controller whether this is the Z-Wave stick
 */
public record ZwaveNode(int id, String name, String location, String status, boolean ready, boolean controller) {
}
