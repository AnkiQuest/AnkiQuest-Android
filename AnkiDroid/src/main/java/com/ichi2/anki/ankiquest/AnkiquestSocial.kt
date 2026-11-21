// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Friends, friend requests, muted players and groups from `GET /api/social/{user}`, and every change
 * to them through `POST /api/social/{user}`, which answers the whole overview again
 * (docs/mobile-api.md, "friends and groups for apps").
 */
internal object AnkiquestSocial {
    data class Person(
        val user: String,
        val display: String,
    )

    data class Group(
        val id: Long,
        val name: String,
        /** Whether the player owns it; only owners see [invite], rename it and remove members. */
        val owner: Boolean,
        val invite: String?,
        val members: List<Person>,
        /** Players the owner invited who have not answered yet; only owners see them. */
        val invited: List<Person> = emptyList(),
    )

    /** A group someone invited the player to, answered with [Change.AcceptInvite] or [Change.DeclineInvite]. */
    data class Invitation(
        val group: Long,
        val name: String,
        /** How many members the group has. */
        val members: Int,
        val from: Person,
    )

    data class Overview(
        val friends: List<Person>,
        /** Requests waiting for the player's answer. */
        val incoming: List<Person>,
        /** The player's requests waiting for an answer. */
        val outgoing: List<Person>,
        val muted: List<Person>,
        val groups: List<Group>,
        /** Whether the player's profile is on the global leaderboard. */
        val public: Boolean,
        /** Groups the player is invited to, newest first. */
        val invitations: List<Invitation> = emptyList(),
    ) {
        fun isMuted(user: String): Boolean = muted.any { it.user == user }

        /** Where the player stands with [user]; friends first, then requests either way. */
        fun relation(user: String): Relation =
            when {
                friends.any { it.user == user } -> Relation.FRIENDS
                incoming.any { it.user == user } -> Relation.ASKED
                outgoing.any { it.user == user } -> Relation.REQUESTED
                else -> Relation.NONE
            }

        /**
         * The groups the player owns that [user] is not in yet: with an invite link to share for
         * [InviteMode.LINK], and not invited yet for [InviteMode.DIRECT].
         */
        fun invitable(
            user: String,
            mode: InviteMode = InviteMode.LINK,
        ): List<Group> =
            groups.filter { group ->
                val ready =
                    when (mode) {
                        InviteMode.LINK -> group.invite != null
                        InviteMode.DIRECT -> group.invited.none { it.user == user }
                    }
                group.owner && ready && group.members.none { it.user == user }
            }

        /** The player's groups that [user] is invited to and has not answered yet. */
        fun invitedTo(user: String): List<Group> = groups.filter { group -> group.owner && group.invited.any { it.user == user } }
    }

    /** How the player invites someone to a group: the server delivers the invitation, or the player shares the link. */
    enum class InviteMode {
        DIRECT,
        LINK,
        ;

        companion object {
            /** [DIRECT] on a server that reports [FEATURE_INVITES], [LINK] on older ones. */
            fun of(features: Set<String>): InviteMode = if (FEATURE_INVITES in features) DIRECT else LINK
        }
    }

    /** The player's friendship with someone else, and what [Change.Befriend] or [Change.Unfriend] does to it. */
    enum class Relation {
        /** Neither friends nor a request: befriending sends a request ([REQUESTED]). */
        NONE,

        /** The player's request waits for their answer: unfriending withdraws it ([NONE]). */
        REQUESTED,

        /** Their request waits for the player: befriending accepts ([FRIENDS]), unfriending declines ([NONE]). */
        ASKED,

        /** Unfriending removes the friend ([NONE]). */
        FRIENDS,
    }

    /** What an invite link shows before joining. */
    data class Invite(
        val name: String,
        val members: Int,
    )

    /** One `POST /api/social/{user}` action, as the server names it. */
    sealed class Change(
        val action: String,
    ) {
        /** Sends a request, or accepts one from [user]. */
        data class Befriend(
            val user: String,
        ) : Change("befriend")

        /** Removes a friend, declines their request or withdraws the player's own. */
        data class Unfriend(
            val user: String,
        ) : Change("unfriend")

        data class Mute(
            val user: String,
        ) : Change("mute")

        data class Unmute(
            val user: String,
        ) : Change("unmute")

        data class CreateGroup(
            val name: String,
        ) : Change("create_group")

        data class RenameGroup(
            val group: Long,
            val name: String,
        ) : Change("rename_group")

        /** A new invite code for [group]; the old one stops working. */
        data class NewInvite(
            val group: Long,
        ) : Change("new_invite")

        data class Join(
            val invite: String,
        ) : Change("join")

        /** Invites [user] to [group], which the player owns; the server tells them. */
        data class Invite(
            val group: Long,
            val user: String,
        ) : Change("invite")

        /** Joins [group], which the player is invited to. */
        data class AcceptInvite(
            val group: Long,
        ) : Change("accept_invite")

        data class DeclineInvite(
            val group: Long,
        ) : Change("decline_invite")

        data class Leave(
            val group: Long,
        ) : Change("leave")

        data class Remove(
            val group: Long,
            val member: String,
        ) : Change("remove")

        data class Visibility(
            val public: Boolean,
        ) : Change("visibility")

        fun body(): JSONObject =
            JSONObject().put("action", action).apply {
                when (val change = this@Change) {
                    is Befriend -> put("user", change.user)
                    is Unfriend -> put("user", change.user)
                    is Mute -> put("user", change.user)
                    is Unmute -> put("user", change.user)
                    is CreateGroup -> put("name", change.name)
                    is RenameGroup -> put("group", change.group).put("name", change.name)
                    is NewInvite -> put("group", change.group)
                    is Join -> put("invite", change.invite)
                    is Invite -> put("group", change.group).put("user", change.user)
                    is AcceptInvite -> put("group", change.group)
                    is DeclineInvite -> put("group", change.group)
                    is Leave -> put("group", change.group)
                    is Remove -> put("group", change.group).put("member", change.member)
                    is Visibility -> put("public", change.public)
                }
            }
    }

    /** The capability of servers that invite a player to a group directly (docs/mobile-api.md). */
    const val FEATURE_INVITES = "group_invites"

    /** The server's limits: group names are 1 to 40 characters, usernames at most 32. */
    const val MAX_GROUP_NAME = 40
    const val MAX_USER = 32

    private const val MAX_INVITE = 128
    private val inviteFormat = Regex("[A-Za-z0-9_-]{1,$MAX_INVITE}")

    suspend fun overview(account: HomeAccount): Overview = parse(AnkiquestApi.call(account, "GET", "api/social/${account.encodedUser}"))

    suspend fun change(
        account: HomeAccount,
        change: Change,
    ): Overview = parse(AnkiquestApi.call(account, "POST", "api/social/${account.encodedUser}", change.body()))

    /** The group behind [code], to show before joining; a code that is not valid any more is `404`. */
    suspend fun invite(
        account: HomeAccount,
        code: String,
    ): Invite =
        withContext(Dispatchers.IO) {
            val url =
                account
                    .url("api/invites")
                    .newBuilder()
                    .addPathSegment(code)
                    .build()
            parseInvite(AnkiquestApi.json(account, AnkiquestApi.request(url, account.token, account.language).build()))
        }

    /** The link that joins a group with [code] on [account]'s server, as the website shares it. */
    fun inviteLink(
        account: HomeAccount,
        code: String,
    ): String =
        account
            .url("join")
            .newBuilder()
            .addPathSegment(code)
            .build()
            .toString()

    /**
     * The invite code in [text]: a bare code, or a `/join/<code>` link on [account]'s server. A link
     * to any other server answers null, so its code is never sent to this one.
     */
    fun inviteCode(
        account: HomeAccount,
        text: String,
    ): String? {
        val typed = text.trim()
        if (inviteFormat.matches(typed)) return typed
        val url = typed.toHttpUrlOrNull() ?: return null
        val server = account.url("").takeIf { it.username.isEmpty() } ?: return null
        if (url.scheme != server.scheme || url.host != server.host || url.port != server.port) return null
        val base = server.pathSegments.filter { it.isNotEmpty() }
        val path = url.pathSegments.filter { it.isNotEmpty() }
        if (path.size != base.size + 2 || path.take(base.size) != base || path[base.size] != "join") return null
        return path.last().takeIf { inviteFormat.matches(it) }
    }

    /** A cleaned group name, or null for one the server would refuse. */
    fun groupName(text: String): String? =
        text.trim().takeIf {
            it.isNotEmpty() && it.length <= MAX_GROUP_NAME &&
                it.none(Char::isISOControl)
        }

    /** A cleaned username to send a request to, or null for an empty one. */
    fun userName(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_USER }

    fun parse(json: JSONObject): Overview =
        Overview(
            friends = people(json.optJSONArray("friends")),
            incoming = people(json.optJSONArray("incoming")),
            outgoing = people(json.optJSONArray("outgoing")),
            muted = people(json.optJSONArray("muted")),
            groups =
                json.optJSONArray("groups")?.objects().orEmpty().mapNotNull { group ->
                    val id = group.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
                    Group(
                        id = id,
                        name = group.text("name"),
                        owner = group.optBoolean("owner"),
                        invite = group.text("invite").ifEmpty { null },
                        members = people(group.optJSONArray("members")),
                        invited = people(group.optJSONArray("invited")),
                    )
                },
            public = json.optBoolean("public"),
            invitations =
                json.optJSONArray("invitations")?.objects().orEmpty().mapNotNull { invitation ->
                    val group = invitation.optLong("group").takeIf { it > 0 } ?: return@mapNotNull null
                    val from = person(invitation.optJSONObject("from")) ?: return@mapNotNull null
                    Invitation(group, invitation.text("name"), invitation.optInt("members"), from)
                },
        )

    fun parseInvite(json: JSONObject): Invite = Invite(json.text("name"), json.optInt("members"))

    private fun people(array: JSONArray?): List<Person> = array?.objects().orEmpty().mapNotNull(::person)

    /** A person as `{ "user", "display" }`, or null without a username. */
    private fun person(json: JSONObject?): Person? {
        val user = json?.text("user")?.ifEmpty { null } ?: return null
        return Person(user, json.text("display").ifEmpty { user })
    }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
}
