local DAMAGE_THRESHOLD = 0.0
local LOG_HITS = false

local hit_counters = {}
local last_check   = {}
local check_count  = 0

function checkArmorInvuln(uuid, damage, armor_tag)
    check_count = check_count + 1

    if java_isGodEntity(uuid) then
        if LOG_HITS then
            java_log("Lua BLOCK [java-reg]: " .. uuid .. " dmg=" .. damage)
        end
        track_block(uuid)
        return true
    end

    if damage <= DAMAGE_THRESHOLD then
        return false
    end

    if is_god_armor(armor_tag) then
        if LOG_HITS then
            java_log("Lua BLOCK [armor-tag]: " .. uuid .. " tag=" .. armor_tag .. " dmg=" .. damage)
        end
        java_registerGod(uuid)
        track_block(uuid)
        return true
    end

    return false
end
local god_armor_tags = {
    ["transfinity_improved:praetor_helmet"]     = true,
    ["transfinity_improved:praetor_chestplate"] = true,
    ["transfinity_improved:praetor_leggings"]   = true,
    ["transfinity_improved:praetor_boots"]      = true,
    ["transfinity_improved:god_armor"]          = true,
    ["unknown"]                                = false,  -- explicit unknown → don't veto
}

function is_god_armor(tag)
    if tag == nil or tag == "" then return false end
    local v = god_armor_tags[tag]
    return v == true
end

function track_block(uuid)
    hit_counters[uuid] = (hit_counters[uuid] or 0) + 1
end

function get_block_count(uuid)
    return hit_counters[uuid] or 0
end

function get_total_checks()
    return check_count
end

java_log("armor_invuln.lua loaded. Lua invuln layer ACTIVE.")
java_log("Total god armor tags registered: " .. (function()
    local c = 0
    for k, v in pairs(god_armor_tags) do if v then c = c + 1 end end
    return c
end)())
