# Core playback results

**Milestone 1 live gate: PENDING. Task 7 device/live execution: NOT_RUN.**

No connected execution was available for this task. Managed YouTube access was previously observed as HTTP 403 in earlier environment investigation; this task did not rerun live music requests or claim that prior observation as a recording-specific result. No device was provisioned, no ADB retry was attempted, and no third-party client, mock response, or generated audio is reported as full-track evidence.

The actionable execution procedure is [core-playback.md](core-playback.md). Automated verification: 160 tests passed with zero failures/errors/skips; Android lint reported zero errors and four baseline warnings. The application and instrumentation APKs were packaged. These results do not establish device or live playback acceptance. No raw lyrics, audio, signatures, credentials, or temporary media URLs belong in this file.

## Environment manifest (fill only from actual execution)

| Field | Value |
| --- | --- |
| Execution UTC date/time and tester | NOT_RUN |
| App version / source manifest SHA-256 / APK SHA-256 | 0.1.0 debug; source `e8430bc94a28bd080c8aae629277448f9e31a4dad6360cc053cb6898a1b49b4e`; APK `3b6eb9f916b6b21302da7039c13a2c8acea1fe65342354eab979379c85c18455` (root build, not device execution) |
| Device model / Android API / ABI | NOT_RUN |
| Connection route / system VPN enabled / region if known | NOT_RUN |
| Catalog/library/resolver versions | Packaged InnerTubeX 0.10.0; source manifest above. Device/live result NOT_RUN |
| Instrumented test APK installed / test outcome | NOT_RUN (APK packaging is a separate automated check) |
| Background and Activity recreation method/outcome | NOT_RUN |

## Exact recording manifest template

Duplicate this block for every actual selected recording, including separately selected ambiguous/alternate versions. Keep literal stable identity/version fields; unknown values stay UNKNOWN and the adapter's empty candidate version stays `""`.

```text
case_id: PENDING
categories: PENDING
executed_at_utc: NOT_RUN
query_exact: PENDING
primary_ytm_outcome_and_fallback_evidence: PENDING
track_id: PENDING
catalog_source_provider_kind_id: PENDING
playback_candidate_id: PENDING
playback_source_provider_kind_id: PENDING
candidate_version_literal: PENDING
recording_title_artist_album: PENDING
release_date_if_recent: PENDING
expected_duration_ms_and_basis: PENDING
artwork_kind_identity_and_render_outcome: NOT_RUN
network_route_vpn_region: PENDING
supported_transport_if_observed: UNKNOWN
initial_play_audible_correct_recording_latency_ms: NOT_RUN
pause_resume_positions_and_audible_outcome: NOT_RUN
forward_seek_requested_observed_ms_and_audio: NOT_RUN
backward_seek_requested_observed_ms_and_audio: NOT_RUN
full_play_zero_to_natural_end_start_end_utc: NOT_RUN
full_play_elapsed_ms_final_position_ms_status: NOT_RUN
complete_audible_ending_or_cutoff: NOT_RUN
lyrics_provider_kind_or_absence_or_error: NOT_RUN
lyrics_timing_beginning_middle_after_seek: NOT_RUN
lyrics_failure_audio_continues_and_A_to_B_race: NOT_RUN
background_and_activity_recreation_metadata_position_audio: NOT_RUN
rapid_search_selection_stale_result_outcome: NOT_RUN
normalized_error_code_and_retry_outcome_if_any: NOT_RUN
observations_without_media_urls_payloads_audio_or_lyrics: PENDING
check_verdicts_catalog_artwork_full_play_controls_lyrics_lifecycle: NOT_RUN
recording_overall_verdict: NOT_RUN
```

Use the technical screen's selected metadata/source/version and observed provider outcome. If an additional stable identity is not exposed in the screen, an authorized local debugger may inspect normalized `Track`/candidate models; never dump runtime leases/requests or provider payloads. Do not fabricate source IDs or claim a recent release from memory.

## Category execution ledger

| Case | Category / starting example | Exact query / selected source ID / version | Catalog & artwork | Full recording & seeks | Lyrics | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| C01 | International / Linkin Park — Numb | PENDING | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C02 | Russian / Кино — Группа крови | PENDING | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C03 | Older / Queen — Bohemian Rhapsody | PENDING | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C04 | Official recent release within 12 months | PENDING live selection | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C05 | Proper YTM release / Daft Punk — Get Lucky | PENDING | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C06 | Ordinary YouTube fallback | PENDING observed primary/fallback case | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C07 | Synced / Numb and Группа крови candidates | PENDING actual provider result | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C08 | Plain-only lyrics | PENDING actual provider result | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C09 | Unavailable lyrics | PENDING actual provider absence | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C10 | Instrumental / Aphex Twin — Avril 14th | PENDING | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C11 | Ambiguous / Hello, Adele vs Lionel Richie | PENDING two selected identities | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| C12 | Versions / Radiohead — Creep original vs live/cover | PENDING separate identities/versions | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |

Task 7 provides no PASS for the live/device gate. Complete this ledger from actual execution before reconsidering milestone readiness. Later queue persistence/proxy/offline acceptance is outside this slice and has not been run.
