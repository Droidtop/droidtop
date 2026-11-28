# Media provider feasibility

Source review for tracker #969. The first scope is direct audio or video files
from a user-selected server or podcast feed. No server or device was run.

The plugin registry's `media.source` point lets a plugin act as a player.
`docs/plugin-api.md` D10 describes the current media-session controls as
built in only. Neither is a browsing, episode, or offline media contract.

| Source | Verified interface | Consequence |
|---|---|---|
| RSS 2.0 | [RSS enclosure specification](https://www.rssboard.org/rss-specification) defines a URL, byte length and media type per enclosure. | A feed adapter can identify downloadable episodes. The feed's advertised size and type are hints and the transfer must still enforce its own limits. |
| Jellyfin | [Official generated library client](https://github.com/jellyfin/jellyfin-sdk-typescript/blob/master/src/generated-client/api/library-api.ts) exposes authenticated item download. | A direct-file adapter is feasible where the selected server and account permit it. Playback compatibility and the account's download permission still need validation. |
| Emby | [Library download API](https://dev.emby.media/reference/RestAPI/LibraryService/getItemsByIdDownload.html) requires user authentication and reports permission denial. | Shares the direct-file transfer shape with Jellyfin. Authentication and server-specific behavior need a separate adapter check. |
| Navidrome | [Subsonic compatibility](https://www.navidrome.org/docs/developers/subsonic-api/) documents song download and a server-dependent download role. | Start with single tracks. Album and playlist archives are outside the direct playable-file path. |
| Plex | [Downloads support](https://support.plex.tv/articles/downloads-overview/) documents supported Plex clients, account eligibility, and server-owner approval. | Official app launch is the supported offline route established by this review. A Droidtop adapter would need separate evidence for third-party downloads. |

Droidtop's existing Downloads job already provides a bounded, cancellable
transfer with resume, destination ownership, and credential-aware redirects.
An offline direct-file source should use that job and keep the source-specific
metadata separate from the local playable file. A feed enclosure on a different
origin needs its own network authorization. It must never inherit the feed's
credential headers. Private servers require the existing local-network grant.

Spotify's [Web Playback SDK](https://developer.spotify.com/documentation/web-playback-sdk)
documents streaming and playback control. The SDK does not establish an offline
file interface for Droidtop. [Apple MusicKit](https://developer.apple.com/musickit/)
documents Android playback, so the report's official-app-only claim is too
broad for streaming. Apple's [program agreement](https://developer.apple.com/support/downloads/terms/apple-developer-program/Apple-Developer-Program-License-Agreement-English.pdf)
restricts downloads to uses permitted by Apple documentation. The
[YouTube developer policies](https://developers.google.com/youtube/terms/developer-policies)
prohibit offline playback through ordinary API clients. These are separate
integration classes from direct-file media.

The [TIDAL developer terms](https://developer.tidal.com/documentation/guidelines/guidelines-developer-terms-3_0)
limit playback through its developer platform to its official player module
and prohibit downloading playback from embeds. [SoundCloud's API terms](https://developers.soundcloud.com/docs/api/terms-of-use)
prohibit API clients from offering offline access, including tracks that
uploaders make downloadable on SoundCloud itself. An endpoint in the API
reference alone does not grant offline rights. [Qobuz service terms](https://www.qobuz.com/se-en/legal/terms)
describe offline import in the Qobuz service. They do not establish an
offline stream API for third-party apps. Treat separately purchased files
that the user already owns as local media.

[Netflix](https://help.netflix.com/en/node/54816) and
[Disney+](https://help.disneyplus.com/en-GB/article/disneyplus-en-eg-downloads)
document downloads through their apps on eligible devices and accounts.
Their support pages establish no third-party offline integration. Opening
an installed app does not grant Droidtop access to its downloaded files.
Deezer and subscription video services beyond these two remain unverified.
The direct-file evidence does not establish adaptive-stream offline
playback or DRM licensing.
