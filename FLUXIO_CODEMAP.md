# FLUXIO_CODEMAP.md
> Commit: 8c1f63f8235e  |  Generado: 2026-05-11 21:51  |  Roadmap: 9/75 completados  |  Archivos totales: 689
> **Este archivo es el INDICE.** Fetchea la parte que necesites para ver los archivos.

---

## Roadmap -- estado

| Paso | Nombre | Estado | Archivos clave |
|---|---|---|---|
| 30a-1 | Foundation — Typography TextAppearances | OK | `typography.xml (rework)` |
| 30a-2 | Foundation — Dimens | OK | `dimens.xml (rework)` |
| 30b-1 | Foundation — Colors paleta | OK | `colors.xml (rework)` |
| 30b-2 | Foundation — Themes | OK | `themes.xml (rework)` |
| 30c-1 | Foundation — Eliminar CookieImageView | OK | `CookieImageView.kt + layouts afectados (rework)` |
| 30c-2 | Foundation — Icon states | OK | `res/drawable/ic_*.xml (rework)` |
| 30d-1 | Foundation — Style rename script | OK | `61 archivos de estilo (rework)` |
| 30e-1 | Player — Layout XML | OK | `fragment_playback_panel.xml (rework)` |
| 30e-2 | Player — Controles y datos | OK | `PlaybackPanelFragment.kt (rework)` |
| 30e-3 | Player — Secundarios, tabs y seek overlay | SIGUIENTE | `PlaybackPanelFragment.kt (rework)` |
| 30e-a-1 | Player LYRICS — Layout y estructura | pendiente | `LyricsTabFragment.kt, fragment_lyrics_tab.xml (rework)` |
| 30e-a-2 | Player LYRICS — Resaltado y scroll | pendiente | `LyricsTabFragment.kt, WordHighlighter.kt (rework)` |
| 30e-b-1 | Player AUDIO — Shell + tarjeta EQ | pendiente | `AudioTabFragment.kt, fragment_audio_tab.xml` |
| 30e-b-2 | Player AUDIO — Crossfade, Stereo, Speed | pendiente | `AudioTabFragment.kt` |
| 30e-b-3 | Player AUDIO — Timer + acordeón | pendiente | `AudioTabFragment.kt` |
| 30f-1 | Mini player — Layout y display | pendiente | `MiniPlayerFragment.kt, fragment_mini_player.xml` |
| 30f-2 | Mini player — Expansión | pendiente | `MiniPlayerFragment.kt, MainFragment.kt` |
| 30g-1 | Library — Item layouts compartidos | pendiente | `ViewHolders.kt, item_*.xml` |
| 30g-2 | Library — Songs y Folders | pendiente | `SongListFragment.kt, FolderListFragment.kt` |
| 30g-3 | Library — Albums y Artists | pendiente | `AlbumListFragment.kt, ArtistListFragment.kt` |
| 30g-4 | Library — Collage | pendiente | `GenreListFragment.kt, PlaylistListFragment.kt + collage component` |
| 30g-5 | Library — Modo selección | pendiente | `SelectionFragment.kt, SelectionIndicatorAdapter.kt` |
| 30g-6 | Library — Scroll thumb | pendiente | `FastScrollRecyclerView.kt` |
| 30h-1 | Album detail | pendiente | `AlbumDetailFragment.kt, fragment_album_detail.xml, AlbumDetailListAdapter.kt` |
| 30h-2 | Artist detail | pendiente | `ArtistDetailFragment.kt, fragment_artist_detail.xml, ArtistDetailListAdapter.kt` |
| 30i-1 | Genre detail | pendiente | `GenreDetailFragment.kt, fragment_genre_detail.xml` |
| 30i-2 | Folder detail + Tag detail (nuevos) | pendiente | `FolderDetailFragment.kt (nuevo), TagDetailFragment.kt (nuevo)` |
| 30i-3 | Playlist detail — Vista | pendiente | `PlaylistDetailFragment.kt, fragment_playlist_detail.xml` |
| 30i-4 | Playlist detail — Modo edición | pendiente | `PlaylistDetailFragment.kt, PlaylistDragCallback.kt` |
| 30j-1 | Menú base — Shell visual | pendiente | `MenuDialogFragmentImpl.kt, MenuItemAdapter.kt` |
| 30j-2 | Menú canción + álbum | pendiente | `Menu.kt, MenuViewModel.kt` |
| 30j-3 | Menús artista, género, carpeta, tag, playlist | pendiente | `Menu.kt` |
| 30j-4 | Sort sheet + Add-to-playlist sheet | pendiente | `SortDialog.kt, AddToPlaylistDialog.kt` |
| 30k-1 | Settings — Root cards | pendiente | `RootPreferenceFragment.kt, fragment_settings_root.xml` |
| 30k-2 | Settings — Appearance + Your music | pendiente | `UIPreferenceFragment.kt, MusicPreferenceFragment.kt` |
| 30k-3 | Settings — Playback + Sound + Lyrics | pendiente | `PersonalizePreferenceFragment.kt, AudioPreferenceFragment.kt, LyricsPreferenceFragment.kt` |
| 30k-4 | Settings — Backup + About | pendiente | `BackupPreferenceFragment.kt, AboutFragment.kt` |
| 30l-1 | Search — Pantalla | pendiente | `SearchFragment.kt, SearchAdapter.kt` |
| 30l-2 | Estados vacíos y error | pendiente | `ErrorDetailsDialog.kt` |
| 30l-3 | Diálogos de playlist | pendiente | `NewPlaylistDialog.kt, RenamePlaylistDialog.kt, DeletePlaylistDialog.kt` |
| 30l-4 | Multi-artist dialog | pendiente | `ShowArtistDialog.kt` |
| 30m-1 | Onboarding — Infraestructura | pendiente | `OnboardingActivity.kt (nuevo)` |
| 30m-2 | Onboarding — Pasos 1 y 2 | pendiente | `fragment_onboarding_1.xml, fragment_onboarding_2.xml` |
| 30m-3 | Onboarding — Pasos 3 y 4 | pendiente | `fragment_onboarding_3.xml, fragment_onboarding_4.xml` |
| 30m-4 | Onboarding — Pasos 5 y 6 | pendiente | `fragment_onboarding_5.xml, fragment_onboarding_6.xml` |
| 30n-1 | Loading screen | pendiente | `SplashFragment.kt` |
| 31-1 | Ambient — Extractor | pendiente | `ColorExtractor.kt (nuevo)` |
| 31-2 | Ambient — Player | pendiente | `DynamicColorManager.kt (nuevo), PlaybackPanelFragment.kt` |
| 31-3 | Ambient — App y nav | pendiente | `DynamicColorManager.kt, HomeFragment.kt, MainFragment.kt` |
| 32-1 | Animaciones — Press scale | pendiente | `Animations.kt` |
| 32-2 | Animaciones — Springs y transiciones | pendiente | `Animations.kt, todos los fragments` |
| 33-1 | Widgets — Infraestructura | pendiente | `WidgetProvider.kt, WidgetComponent.kt` |
| 33-2 | Widgets — pane_wide + pane_thin | pendiente | `widget_pane_wide.xml, widget_pane_thin.xml` |
| 33-3 | Widgets — wafer_wide + wafer_thin | pendiente | `widget_wafer_wide.xml, widget_wafer_thin.xml` |
| 33-4 | Widgets — docked_wide + docked_thin | pendiente | `widget_docked_wide.xml, widget_docked_thin.xml` |
| 33-5 | Widgets — stick_wide + stick_thin | pendiente | `widget_stick_wide.xml, widget_stick_thin.xml` |
| 35-1 | Android Auto | pendiente | `MusicBrowser.kt` |
| 36a-1 | Nav bar — Layout | pendiente | `fragment_main.xml, MainFragment.kt` |
| 36a-2 | Nav bar — Lógica | pendiente | `MainFragment.kt` |
| 37-1 | Smart shuffle — Motor | pendiente | `SmartShuffleEngine.kt (nuevo), PlaybackViewModel.kt` |
| 37-2 | Smart shuffle — Toggle UI | pendiente | `PlaybackPanelFragment.kt` |
| 38-1 | Smart playlists — Motor | pendiente | `SmartPlaylistGenerator.kt (nuevo)` |
| 38-2 | Smart playlists — UI | pendiente | `SmartListFragment.kt (nuevo), SmartPlaylistDetailFragment.kt (nuevo)` |
| 40-1 | Playback speed — Motor | pendiente | `PlaybackViewModel.kt` |
| 40-2 | Playback speed — Letras | pendiente | `LyricsRepository.kt` |
| 41-1 | Headset gestures — Detección | pendiente | `HeadsetGestureHandler.kt (nuevo)` |
| 41-2 | Headset gestures — Config | pendiente | `AudioPreferenceFragment.kt, preference_playback.xml` |
| 42-1 | Stats — Estructura + Card Global | pendiente | `StatsFragment.kt (nuevo), StatsViewModel.kt` |
| 42-2 | Stats — Card Actividad | pendiente | `StatsFragment.kt, ActivityChartView.kt (nuevo)` |
| 42-3 | Stats — Card Pódium | pendiente | `StatsFragment.kt` |
| 42-4 | Stats — Card Historial + vacío | pendiente | `StatsFragment.kt, StatsViewModel.kt` |
| 43-1 | F-Droid — Metadatos | pendiente | `PRIVACY.md (nuevo), build.gradle, metadata/` |
| 43-2 | F-Droid — Submission | pendiente | `fastlane/metadata/` |
| 77-1 | Translations — Script | pendiente | `res/values-*/strings.xml (56 archivos)` |
| 78-1 | About screen | pendiente | `AboutFragment.kt` |

---

## Partes del CODEMAP

| Parte | Contenido | Archivos | Link |
|---|---|---|---|
| **Codigo principal** | CI & Config | Material Components | Playback & Audio DSP | Lyrics | Statistics | 85 | [CODEMAP_part1.md](https://raw.githubusercontent.com/Anasimandro10/fluxio/8c1f63f8235efc905b4ea3f166bc601d39316a05/FLUXIO_CODEMAP_part1.md) |
| **UI & Features** | UI Details/Library/Search/Settings | Tags | Backup | Image | Music/Data | Lists | Widgets | App Root | Otros | 207 | [CODEMAP_part2.md](https://raw.githubusercontent.com/Anasimandro10/fluxio/8c1f63f8235efc905b4ea3f166bc601d39316a05/FLUXIO_CODEMAP_part2.md) |
| **Resources** | Drawables (XML vectoriales) | Layouts | Values | Preferences XML | Menus | Navigation | Other | 240 | [CODEMAP_part3.md](https://raw.githubusercontent.com/Anasimandro10/fluxio/8c1f63f8235efc905b4ea3f166bc601d39316a05/FLUXIO_CODEMAP_part3.md) |
| **Fastlane / Metadata** | Descripciones de Play Store / F-Droid en todos los idiomas | 157 | [CODEMAP_part4.md](https://raw.githubusercontent.com/Anasimandro10/fluxio/8c1f63f8235efc905b4ea3f166bc601d39316a05/FLUXIO_CODEMAP_part4.md) |

---

## Detalle por parte

### Part 1 — [Codigo principal](https://raw.githubusercontent.com/Anasimandro10/fluxio/8c1f63f8235efc905b4ea3f166bc601d39316a05/FLUXIO_CODEMAP_part1.md) (85 archivos)
  - CI & Config (3 archivos)
  - Material Components (patched) (6 archivos)
  - Playback & Audio DSP (65 archivos)
  - Lyrics (8 archivos)
  - Statistics (3 archivos)

### Part 2 — [UI & Features](https://raw.githubusercontent.com/Anasimandro10/fluxio/8c1f63f8235efc905b4ea3f166bc601d39316a05/FLUXIO_CODEMAP_part2.md) (207 archivos)
  - UI -- Details (25 archivos)
  - UI -- Library (25 archivos)
  - UI -- Search (6 archivos)
  - UI -- Settings (15 archivos)
  - Tags (5 archivos)
  - Backup (1 archivos)
  - Image (19 archivos)
  - Music / Data (26 archivos)
  - UI -- Lists (shared) (26 archivos)
  - UI -- Shared (21 archivos)
  - Widgets (4 archivos)
  - App Root (11 archivos)
  - Otros (23 archivos)

### Part 3 — [Resources](https://raw.githubusercontent.com/Anasimandro10/fluxio/8c1f63f8235efc905b4ea3f166bc601d39316a05/FLUXIO_CODEMAP_part3.md) (240 archivos)
  - Resources -- Drawables (92 archivos)
  - Resources -- Layouts (71 archivos)
  - Resources -- Values (28 archivos)
  - Resources -- Preferences XML (13 archivos)
  - Resources -- Menus (20 archivos)
  - Resources -- Navigation (2 archivos)
  - Resources -- Other (14 archivos)

### Part 4 — [Fastlane / Metadata](https://raw.githubusercontent.com/Anasimandro10/fluxio/8c1f63f8235efc905b4ea3f166bc601d39316a05/FLUXIO_CODEMAP_part4.md) (157 archivos)
  - Fastlane / Metadata (157 archivos)
