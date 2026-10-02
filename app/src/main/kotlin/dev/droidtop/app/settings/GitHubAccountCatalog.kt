package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.integrations.PluginRepoDetection
import dev.droidtop.library.integrations.PluginRepoUpdates
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.net.DeviceCode
import dev.droidtop.net.DeviceFlowOutcome
import dev.droidtop.net.GitHubOAuth
import dev.droidtop.net.GitHubTokenOrigin
import dev.droidtop.net.GitHubTokenStore
import dev.droidtop.net.SaveTokenResult
import dev.droidtop.net.SignInResult
import dev.droidtop.pluginhost.PluginRepoKeys
import dev.droidtop.pluginhost.PluginRepos
import dev.droidtop.pluginhost.PluginSourceKeys
import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.pluginhost.UserOriginKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The GitHub sign-in and the plugin repositories it unlocks (docs/SPEC.md
 * 12a "Signing in with GitHub" and "Plugin repositories"). One control
 * replaces the old token text field: sign in on github.com with a short
 * code (the OAuth device flow), or paste a token. Both end in the same
 * Keystore-backed [GitHubTokenStore]. The repositories screen adds a
 * repository by name, shows its key for a plain confirmation, and then
 * keeps its plugins updated.
 */
object GitHubAccountCatalog {
    const val SCREEN_ACCOUNT = "accounts_github"
    const val SCREEN_REPOS = "plugin_repositories"

    // ----- the sign-in in progress (one at a time, process-wide) -----

    private object Session {
        @Volatile var active = false
        @Volatile var cancelled = false

        /** Held between entering and leaving the screen: the last paste's outcome, shown once as a row. */
        @Volatile var pasteResult: String? = null
        @Volatile var code: DeviceCode? = null
    }

    /** Called when the person leaves the screen (B): stops a sign-in that is still waiting. */
    private fun cancelSignIn() {
        if (Session.active) Session.cancelled = true
    }

    private fun countdown(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun prompt(code: DeviceCode, secondsLeft: Int?): String =
        "Enter ${code.userCode} at ${code.verificationUri.removePrefix("https://")} on any device" +
            (secondsLeft?.let { " (${countdown(it)} left)" } ?: "") + ". B cancels."

    /** The words for how a sign-in ended. */
    private fun describe(result: SignInResult): String = when (result) {
        is SignInResult.SignedIn ->
            result.credential.login?.let { "Signed in as $it." } ?: "Signed in. GitHub did not say which account just now."
        is SignInResult.Ended -> when (val outcome = result.outcome) {
            DeviceFlowOutcome.Denied -> "You pressed Deny on GitHub. Nothing was saved."
            DeviceFlowOutcome.Expired -> "The code ran out before it was approved. Start again for a new one."
            DeviceFlowOutcome.Cancelled -> "Sign-in cancelled. Nothing was saved."
            is DeviceFlowOutcome.Offline -> "Lost the connection to GitHub (${outcome.reason}). Try again when you are online."
            is DeviceFlowOutcome.Failed -> "${outcome.reason}."
            is DeviceFlowOutcome.Granted -> "Signed in."
        }
        is SignInResult.CouldNotStart ->
            if (result.offline) "Can't reach GitHub (${result.reason}). Try again when you are online." else "${result.reason}."
        SignInResult.StorageRefused -> "This device's secure storage refused to keep the token, so nothing was saved."
    }

    private fun signIn(context: Context, scope: String, onStatus: (String) -> Unit): String {
        if (Session.active) return "A sign-in is already waiting for its code."
        Session.active = true
        Session.cancelled = false
        return try {
            val result = GitHubTokenStore.account(context).signIn(
                scope = scope,
                isCancelled = { Session.cancelled },
                onCode = { code ->
                    Session.code = code
                    onStatus(prompt(code, code.expiresInSeconds))
                },
                onWaiting = { left -> Session.code?.let { onStatus(prompt(it, left)) } },
            )
            if (result is SignInResult.SignedIn) runCatching { PluginRepoDetection.refresh(context, force = true) }
            describe(result)
        } finally {
            Session.active = false
            Session.code = null
        }
    }

    // ----- the account screen -----

    /** The Accounts row: "GitHub", with who is signed in. Reads preferences only, so it is safe to call on the main thread. */
    fun accountRow(context: Context): NestedScreenItem {
        val login = GitHubTokenStore.login(context)
        val signedIn = GitHubTokenStore.isSet(context)
        return NestedScreenItem(
            id = "accounts_github_token",
            title = "GitHub",
            inline = accountScreen(),
            valueLabel = {
                when {
                    !signedIn -> "Not signed in"
                    login != null -> "Signed in as $login"
                    else -> "Signed in"
                }
            },
        )
    }

    /**
     * The one GitHub account screen (docs/SPEC.md 12a): who is signed in, a way in (the device flow or a pasted
     * token) and Sign out. The private-repository permission is asked from the repository screen, only when a
     * private repository is added.
     */
    fun accountScreen() = CatalogScreen(
        id = SCREEN_ACCOUNT,
        title = "GitHub",
        subtitle = "Used only for plugin sources on GitHub",
        onLeave = ::cancelSignIn,
        groups = { context ->
            val (credential, stored) = withContext(Dispatchers.IO) {
                GitHubTokenStore.credential(context) to GitHubTokenStore.isSet(context)
            }
            listOf(
                CatalogGroup(
                    id = "github_account",
                    title = null,
                    items = buildList {
                        add(
                            ActionItem(
                                id = "github_status",
                                title = when {
                                    credential != null -> credential.login?.let { "Signed in as $it" } ?: "Signed in"
                                    stored -> "Saved token unreadable"
                                    else -> "Not signed in"
                                },
                                subtitle = if (stored && credential == null) "Sign in again" else null,
                                run = {},
                            ),
                        )
                        add(
                            AsyncActionItem(
                                id = "github_signin_public",
                                title = "Sign in with GitHub",
                                subtitle = "Enter a short code at github.com/login/device",
                                run = { ctx, onStatus -> signIn(ctx, GitHubOAuth.SCOPE_PUBLIC, onStatus) },
                            ),
                        )
                        Session.pasteResult?.let { message ->
                            add(ActionItem(id = "github_paste_result", title = message, run = { Session.pasteResult = null }))
                        }
                        add(
                            TextInputItem(
                                id = "github_token_value",
                                title = "Paste a token",
                                subtitle = "Checked with GitHub before it is kept",
                                // Never shows a stored value back: the field is for pasting a new one.
                                value = "",
                                secret = true,
                                onChange = { c, v ->
                                    if (v.isNotBlank()) {
                                        Session.pasteResult = withContext(Dispatchers.IO) { pasteMessage(GitHubTokenStore.account(c).savePasted(v)) }
                                    }
                                },
                            ),
                        )
                        if (stored) {
                            add(
                                ActionItem(
                                    id = "github_token_remove",
                                    title = "Sign out",
                                    confirmTitle = "Sign out of GitHub on this device?",
                                    run = { ctx -> GitHubTokenStore.clear(ctx) },
                                ),
                            )
                        }
                    },
                ),
            )
        },
    )

    private fun pasteMessage(result: SaveTokenResult): String = when (result) {
        is SaveTokenResult.Saved ->
            if (result.loginKnown) "Token saved. Signed in as ${result.credential.login}." else "Token saved, but GitHub could not be asked whose it is just now."
        SaveTokenResult.Rejected -> "GitHub rejected that token (expired, revoked or mistyped). Nothing was saved."
        SaveTokenResult.StorageRefused -> "This device's secure storage refused to keep the token, so nothing was saved."
        SaveTokenResult.Blank -> "Nothing to save."
    }

    // ----- plugin repositories -----

    /** What the person asked to trust, held between "look up its key" and the confirmation. */
    private class Proposal(val repo: String, val decision: PluginRepos.TrustDecision, val sources: String)

    private var pendingRepoName = ""
    private var pendingProposal: Proposal? = null

    /** Set when the last look-up failed in a way a private-repository permission could fix; asked for only then. */
    private var pendingNeedsPrivate = false

    /** What the repositories screen reads from stores and preferences, once, off the main thread. */
    private class RepoScreenData(
        val repos: List<UserOriginKey>,
        val signedIn: Boolean,
        val checked: Map<String, Boolean>,
        val auto: Boolean,
        val prereleases: Boolean,
        val found: List<dev.droidtop.library.integrations.DetectedRepo>,
    )

    private fun fingerprintOf(keyBase64: String) = UserOriginKeys.fingerprint(keyBase64) ?: "unreadable"

    fun reposRow(trusted: Int): NestedScreenItem = NestedScreenItem(
        id = "plugin_repositories",
        title = "Plugin repositories",
        subtitle = "Add a GitHub repository by name: droidtop trusts the key it publishes and keeps its plugins up to date",
        inline = reposScreen(),
        valueLabel = { if (trusted == 0) "None added" else "$trusted added" },
    )

    fun reposScreen() = CatalogScreen(
        id = SCREEN_REPOS,
        title = "Plugin repositories",
        subtitle = "Plugins signed with a repository's key show its name",
        groups = { context ->
            // Stores and preferences are read once here, off the main thread; the closures below only use the results.
            val read = withContext(Dispatchers.IO) {
                val keys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
                val repos = PluginRepos.trustedRepos(keys)
                RepoScreenData(
                    repos = repos,
                    signedIn = GitHubTokenStore.isSet(context),
                    checked = repos.associate { it.repo.orEmpty() to (PluginRepoUpdates.lastResult(context, it.repo.orEmpty()) != null) },
                    auto = PluginRepoUpdates.autoUpdate(context),
                    prereleases = PluginRepoUpdates.includePrereleases(context),
                    found = PluginRepoDetection.cached(context),
                )
            }
            val repos = read.repos
            listOfNotNull(
                CatalogGroup(
                    id = "repos_list",
                    title = "Repositories you trust",
                    items = if (repos.isEmpty()) {
                        listOf(ActionItem(id = "repos_none", title = "None yet", subtitle = "Add one below", run = {}))
                    } else {
                        repos.map { entry ->
                            NestedScreenItem(
                                id = "repo_${entry.repo}",
                                title = entry.repo.orEmpty(),
                                subtitle = "Origin ${entry.origin}, key ${fingerprintOf(entry.keyBase64)}",
                                inline = repoScreen(entry),
                                valueLabel = { if (read.checked[entry.repo.orEmpty()] == true) "Checked" else "Not checked" },
                            )
                        }
                    },
                ),
                CatalogGroup(
                    id = "repos_add",
                    title = "Add a repository",
                    items = listOf(
                        TextInputItem(
                            id = "repos_add_name",
                            title = "Repository",
                            subtitle = "owner/name, or its address",
                            value = pendingRepoName,
                            onChange = { _, v -> pendingRepoName = v.trim() },
                        ),
                        AsyncActionItem(
                            id = "repos_add_lookup",
                            title = "Look up its key",
                            subtitle = "Trusts nothing yet",
                            run = { ctx, onStatus -> lookUp(ctx, pendingRepoName, onStatus) },
                        ),
                    ) + listOfNotNull(
                        if (!pendingNeedsPrivate) null else AsyncActionItem(
                            id = "repos_add_private",
                            title = "Allow private repositories",
                            subtitle = "Asks GitHub for read access to private repositories, then looks again",
                            run = { ctx, onStatus ->
                                val message = signIn(ctx, GitHubOAuth.SCOPE_PRIVATE_REPOS, onStatus)
                                if (GitHubTokenStore.credential(ctx)?.scope == GitHubOAuth.SCOPE_PRIVATE_REPOS) {
                                    pendingNeedsPrivate = false
                                    message + " " + lookUp(ctx, pendingRepoName, onStatus)
                                } else {
                                    message
                                }
                            },
                        ),
                    ),
                ),
                pendingProposal?.let(::proposalGroup),
                foundGroup(read),
                CatalogGroup(
                    id = "repos_updates",
                    title = "Updates",
                    items = listOf(
                        ToggleItem(
                            id = "repos_auto",
                            title = "Update plugins from these repositories automatically",
                            subtitle = "On the Software updates schedule, unmetered connections only",
                            current = read.auto,
                            onToggle = { c, value -> PluginRepoUpdates.setAutoUpdate(c, value) },
                        ),
                        ToggleItem(
                            id = "repos_prereleases",
                            title = "Include pre-releases",
                            subtitle = "Off: pre-releases are ignored",
                            current = read.prereleases,
                            onToggle = { c, value -> PluginRepoUpdates.setIncludePrereleases(c, value) },
                        ),
                    ),
                ),
            )
        },
    )

    /** "Found for you": repositories the signed-in person can reach that look like plugin repositories, from the cached detection; trusting one goes through the same confirmation. */
    private fun foundGroup(read: RepoScreenData): CatalogGroup? {
        if (!read.signedIn) return null
        val items = buildList<CatalogItem> {
            read.found.filter { f -> read.repos.none { PluginRepos.sameRepo(it.repo, f.repo) } }.forEach { f ->
                add(
                    AsyncActionItem(
                        id = "repos_found_${f.repo}",
                        title = f.repo,
                        subtitle = (if (f.isPrivate) "Private. " else "") + "Look up its committed key and review it before trusting",
                        run = { ctx, onStatus ->
                            pendingRepoName = f.repo
                            lookUp(ctx, f.repo, onStatus)
                        },
                    ),
                )
            }
            if (isEmpty()) {
                add(ActionItem(id = "repos_found_none", title = "Nothing found yet", subtitle = "Repositories you can access with the topic ${PluginRepoDetection.TOPIC}, or a plugin name and a published key, show up here", run = {}))
            }
            add(
                AsyncActionItem(
                    id = "repos_found_refresh",
                    title = "Look again",
                    subtitle = "Reads the repositories you can access (cached for hours; it never installs anything on them)",
                    run = { ctx, onStatus ->
                        onStatus("Reading your repositories...")
                        describeDetection(PluginRepoDetection.refresh(ctx, force = true))
                    },
                ),
            )
        }
        return CatalogGroup(id = "repos_found", title = "Found for you", items = items)
    }

    private fun describeDetection(outcome: PluginRepoDetection.Outcome): String = when (outcome) {
        is PluginRepoDetection.Outcome.Done -> "Found ${outcome.found.size} plugin repositor${if (outcome.found.size == 1) "y" else "ies"} you can access."
        PluginRepoDetection.Outcome.NotSignedIn -> "Sign in to GitHub first."
        is PluginRepoDetection.Outcome.RateLimited -> "GitHub's request limit is used up for now; the earlier list is kept."
        is PluginRepoDetection.Outcome.Failed -> "Couldn't read your repositories (${outcome.reason})."
    }

    private fun lookUp(context: Context, input: String, onStatus: (String) -> Unit): String {
        val repo = PluginRepos.parse(input) ?: return "Type the repository as owner/name, like octocat/hello-world."
        onStatus("Reading the key committed to $repo...")
        pendingProposal = null
        pendingNeedsPrivate = false
        val credential = GitHubTokenStore.credential(context)
        return when (val fetched = PluginRepoKeys.fetch(repo, credential?.token)) {
            is PluginRepoKeys.Result.Failed -> {
                // Only a device-flow sign-in (or none) can be widened here; a pasted token is replaced under Accounts and sources.
                pendingNeedsPrivate = credential == null ||
                    (credential.origin == GitHubTokenOrigin.DEVICE_FLOW && credential.scope != GitHubOAuth.SCOPE_PRIVATE_REPOS)
                val privateHint = if (pendingNeedsPrivate) " If $repo is private, allow private repositories below." else ""
                "Refused: " + fetched.reason + privateHint
            }
            is PluginRepoKeys.Result.Fetched -> {
                val published = fetched.found.key
                val decision = PluginRepos.decide(repo, published, UserOriginKeys.load(UserOriginKeys.storeFile(context)))
                pendingProposal = Proposal(repo, decision, fetched.found.describeSources(repo))
                when (decision) {
                    is PluginRepos.TrustDecision.Propose, is PluginRepos.TrustDecision.Adopt -> "Review its key below. Nothing is trusted until you confirm."
                    is PluginRepos.TrustDecision.AlreadyTrusted -> "$repo is already trusted with this same key."
                    is PluginRepos.TrustDecision.KeyChanged -> "Refused: $repo now publishes a different key than the one you trusted."
                    is PluginRepos.TrustDecision.OriginChanged -> "Refused: $repo now names a different origin than the one you trusted."
                    is PluginRepos.TrustDecision.OriginTaken -> "Refused: that origin is already trusted for ${decision.byRepo}."
                }
            }
        }
    }

    private fun proposalGroup(proposal: Proposal): CatalogGroup {
        val repo = proposal.repo
        val items = when (val decision = proposal.decision) {
            is PluginRepos.TrustDecision.Propose -> confirmItems(repo, decision.published, adopt = false, sources = proposal.sources)
            is PluginRepos.TrustDecision.Adopt -> confirmItems(repo, decision.published, adopt = true, sources = proposal.sources)
            is PluginRepos.TrustDecision.AlreadyTrusted -> listOf(
                ActionItem(id = "repos_proposal_info", title = "$repo is already trusted", subtitle = "Same key, fingerprint ${fingerprintOf(decision.published.keyBase64)}", run = {}),
            )
            is PluginRepos.TrustDecision.KeyChanged -> listOf(
                ActionItem(
                    id = "repos_proposal_info",
                    title = "REFUSED: $repo now publishes a different key",
                    subtitle = "You trusted ${decision.trustedFingerprint}; it now publishes ${decision.publishedFingerprint}. That can mean the owner rotated it, " +
                        "or that the repository was taken over. droidtop does not replace it from here. If you are sure, remove the repository " +
                        "and add it again; plugins signed with the old key stop running when you remove it.",
                    run = {},
                ),
            )
            is PluginRepos.TrustDecision.OriginChanged -> listOf(
                ActionItem(
                    id = "repos_proposal_info",
                    title = "REFUSED: $repo now names a different origin",
                    subtitle = "You trusted it as \"${decision.trustedOrigin}\"; it now publishes \"${decision.publishedOrigin}\". Remove the repository and add it again if that is expected.",
                    run = {},
                ),
            )
            is PluginRepos.TrustDecision.OriginTaken -> listOf(
                ActionItem(
                    id = "repos_proposal_info",
                    title = "REFUSED: origin \"${decision.origin}\" belongs to ${decision.byRepo}",
                    subtitle = "One origin can be trusted for one repository. Remove that repository first if this one replaces it.",
                    run = {},
                ),
            )
        }
        return CatalogGroup(
            id = "repos_proposal",
            title = "Review before trusting",
            items = items + ActionItem(id = "repos_proposal_discard", title = "Discard", subtitle = "Trust nothing, change nothing", run = { pendingProposal = null }),
        )
    }

    private fun confirmItems(repo: String, published: PluginSourceKeys.PublishedKey, adopt: Boolean, sources: String): List<CatalogItem> = listOf(
        ActionItem(
            id = "repos_proposal_info",
            title = "Trust the plugin repository $repo?",
            subtitle = "Origin \"${published.origin}\", key fingerprint ${fingerprintOf(published.keyBase64)}. Where the key comes from: $sources. Trusting means: plugins signed with this key can be " +
                "installed and are updated automatically from $repo, and they show \"Verified by: $repo\". Each plugin still runs only after you " +
                "approve it, and anything an update newly asks for asks you first. droidtop has not vetted this repository and cannot tell whose key it " +
                "is; compare the fingerprint with one the author published if you can.",
            run = {},
        ),
        AsyncActionItem(
            id = "repos_proposal_trust",
            title = "Trust $repo",
            subtitle = if (adopt) "The key is already trusted; this records $repo as its repository" else "Stores this key and adds the repository",
            confirmTitle = "Trust $repo and everything signed with this key?",
            run = { ctx, _ -> commitTrust(ctx, repo, published, adopt) },
        ),
    )

    private fun commitTrust(context: Context, repo: String, published: PluginSourceKeys.PublishedKey, adopt: Boolean): String {
        val store = UserOriginKeys.storeFile(context)
        val message = if (adopt) {
            if (UserOriginKeys.attachRepo(store, published.origin, repo)) "Recorded $repo as the repository of \"${published.origin}\"." else "Couldn't save that."
        } else {
            when (val outcome = UserOriginKeys.add(store, published.origin, published.keyBase64, PluginRepos.sourceUrl(repo), repo)) {
                is dev.droidtop.pluginhost.AddKeyOutcome.Added -> "Trusting $repo. Its plugins show \"Verified by: $repo\" and are updated automatically."
                dev.droidtop.pluginhost.AddKeyOutcome.AlreadyTrustedSameKey -> "That key is already trusted."
                is dev.droidtop.pluginhost.AddKeyOutcome.KeyChanged -> "Refused: a different key is already trusted for \"${published.origin}\"."
                is dev.droidtop.pluginhost.AddKeyOutcome.Refused -> "Refused: ${outcome.reason}"
            }
        }
        pendingProposal = null
        pendingRepoName = ""
        return message
    }

    private fun repoScreen(entry: UserOriginKey): CatalogScreen {
        val repo = entry.repo.orEmpty()
        return CatalogScreen(
            id = "plugin_repo_$repo",
            title = repo,
            subtitle = "Origin ${entry.origin}, key ${fingerprintOf(entry.keyBase64)}",
            groups = { context ->
                // What the last check saw: no network here, a screen's rows are rebuilt on every change.
                val (last, latest) = withContext(Dispatchers.IO) {
                    PluginRepoUpdates.lastResult(context, repo) to PluginRepoUpdates.cachedRelease(context, repo)
                }
                listOf(
                    CatalogGroup(
                        id = "repo_status",
                        title = null,
                        items = listOf(
                            ActionItem(
                                id = "repo_last",
                                title = last ?: "Not checked yet",
                                subtitle = "Plugins signed with this key show \"Verified by: $repo\"",
                                run = {},
                            ),
                            AsyncActionItem(
                                id = "repo_check_now",
                                title = "Check for updates now",
                                subtitle = "Installs a newer version of a plugin you already have, signed with this key",
                                run = { ctx, onStatus ->
                                    onStatus("Checking $repo...")
                                    PluginRepoUpdates.describe(PluginRepoUpdates.checkNow(ctx, entry))
                                },
                            ),
                        ),
                    ),
                    CatalogGroup(
                        id = "repo_bundles",
                        title = "In the latest release${latest?.let { " (${it.first})" }.orEmpty()}",
                        items = latest?.second?.map { assetName ->
                            AsyncActionItem(
                                id = "repo_install_$assetName",
                                title = assetName.removeSuffix(".droidplugin.tar.xz"),
                                subtitle = "Install it. A new plugin arrives unapproved and runs only after you approve it",
                                run = { ctx, onStatus ->
                                    onStatus("Downloading...")
                                    PluginRepoUpdates.installFromRepo(ctx, entry, assetName)
                                },
                            )
                        } ?: listOf(ActionItem(id = "repo_no_release", title = "Nothing listed yet", subtitle = "Check for updates to see what its latest release carries", run = {})),
                    ),
                    CatalogGroup(
                        id = "repo_remove",
                        title = null,
                        items = listOf(
                            ActionItem(
                                id = "repo_stop",
                                title = "Stop trusting $repo",
                                subtitle = "Plugins it signed stop running and are no longer updated, until you trust it again",
                                confirmTitle = "Stop trusting $repo?",
                                run = { ctx ->
                                    UserOriginKeys.remove(UserOriginKeys.storeFile(ctx), entry.origin)
                                    PluginRepoUpdates.forget(ctx, repo)
                                },
                            ),
                        ),
                    ),
                )
            },
        )
    }
}
