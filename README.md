# Small Useful Page 小万事页

Minimal bilingual browser tools. The first tool compresses JPEG, PNG, and WebP images locally in a Web Worker; files are never uploaded.

Sidebar tabs each have a distinct light background color, with an outline marking the active section.

## Development

```sh
npm install
cp .env.example .env.local
npm run dev
```

Run MongoDB locally on `mongodb://127.0.0.1:27017`, or set `MONGODB_URI` before starting the app. Notes use database `smallanduseful`, collection `notes`; `MONGODB_DATABASE` overrides the database name for isolated environments.

Git excludes local environment files (including `.env.test`), Android signing stores (`*.jks`, `*.keystore`), `key.properties`, and Android local/build state. Keep `.env.example` free of credentials; ignore rules do not protect files already tracked by Git or added with `git add -f`.

## Notes and support

The **eBay Support / eBay 客服** tab opens [eBay's official contact flow](https://www.ebay.com/help/call_us?type=ContactUs&topicId=4000) in a new tab. eBay may ask you to sign in or select an issue before offering contact options.

Notes are a **public shared board**, not account-private storage. Anyone who can reach the site can read, edit, or delete them; do not store sensitive information. Reload Notes in another browser to see the latest shared data. Individual note writes avoid replacing unrelated notes from another browser; simultaneous edits to the same note use the last successful write.

All saved notes live on the shared server in MongoDB. There is no browser-only storage, browser backup board, or import-to-shared action. Saves are confirmed only after the server writes to MongoDB; failed saves keep the editor open for retry. If shared notes cannot load, writing is disabled until loading succeeds. Old browser-only data is not read or republished.


## Chinese → English and Fan yi Xia

**Fan yi Xia** is a separate native Android app in `mobile/fan-yi-xia/`. Microphone capture has moved out of the website. The **Chinese → English / 中文译英文** tab at `/translate` retains typed Chinese editing, Qwen translation, and sound-alike phrase suggestions, and adds the public conversation library and saved Qwen analysis history.

### Offline Android capture and translation

Fan yi Xia uses multilingual **Whisper-base through whisper.cpp** for Mandarin transcription and **ML Kit Chinese → English** for translation, both on the phone. This avoids depending on Android speech services that may not provide an offline Mandarin model. ML Kit is intended for everyday translation; review names, numbers, and important wording. It is not the same model or quality as server-side Qwen.

First-time model preparation requires internet and sufficient device storage: Whisper-base is roughly 148 MB, plus ML Kit language models. After successful preparation, recording, transcription, translation, and local saves work without Wi-Fi, cellular data, or access to smallanduseful. Keep the app open while recording; capture is limited to 60 seconds. Leaving the capture screen, including rotation, stops and discards active audio. Review/edit Chinese and translate again before saving. Saved conversations remain on the phone across app restarts; unsaved drafts are memory-only. Clearing app storage or uninstalling removes local data.

Publishing is a separate, explicit online action. **The library is public**, like Notes: anyone who can reach the server can read published Chinese, English, and Qwen results. Do not publish sensitive conversations. Local saves do not automatically publish. Only text is sent; audio is never uploaded. A failed publish retains the local conversation for retry. Publication uses an immutable session UUID, so retrying after a lost response does not create duplicates or overwrite different text.

Build with Android Studio or JDK 17 and the included Gradle wrapper:

```sh
cd mobile/fan-yi-xia
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Android SDK 35 / build tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1; Android 8.0/API 26 or newer, arm64-v8a or x86_64. Gradle downloads the pinned whisper.cpp source during the native build. The debug APK is for direct installation/development; a distributable release requires your own signing key. The app defaults to `https://smallanduseful.lystic.dev`; set its server URL to another HTTPS deployment if needed. Build/install the app and deploy the updated web server before publishing.

References: [whisper.cpp](https://github.com/ggml-org/whisper.cpp), [ML Kit Android translation and model management](https://developers.google.com/ml-kit/language/translation/android).

### Shared library and Qwen reanalysis

Publish a locally saved session from Fan yi Xia, then refresh `/translate` in smallanduseful. Select a conversation to view its original Chinese and English. **Summarize with Qwen** creates a saved summary; enter a custom instruction for **Run reanalysis**. Rerun either action to create another result. Results have timestamps and instructions, remain separate from the source translation, and are visible across browsers. The viewer shows the latest 50 results per conversation and loads the conversation list in pages of 50. AI output can be wrong.

MongoDB stores conversations in `translation_sessions` and results in `translation_analyses`, using the same `MONGODB_URI` and `MONGODB_DATABASE` configuration as Notes. These are separate from the notes board and survive web pod restarts. The public API is `GET /api/translation-sessions?page=0`, `PUT /api/translation-sessions/{uuid}`, `GET /api/translation-sessions/{uuid}`, and `POST /api/translation-sessions/{uuid}/analysis`. Browser writes must be same-origin; native HTTPS publishing does not need a browser CORS exception. There are no accounts or private access controls.

Qwen summaries, reanalysis, typed web translation, and phonetic suggestions require a connection to the self-hosted chat-completions endpoint; there is no cloud fallback. Development loads `SELF_HOSTED_AI_CHAT_COMPLETIONS_URL` and `SELF_HOSTED_AI_CHAT_COMPLETIONS_MODEL` from `.env.local`; production receives them from the deployment manifest. `SELF_HOSTED_AI_API_KEY` optionally supplies a private bearer token. Requests time out after 120 seconds, with a longer ingress read timeout for cold model loading. Qwen reanalysis receives the stored **English text**, not the Chinese transcript or audio. Failures preserve the original conversation and earlier results.

Limits: 4,000 UTF-16 characters of Chinese, 16,000 of saved English, 160 in a session title, 2,000 in a custom analysis instruction, 24 in a web sound-alike selection. Input edits and IME composition invalidate stale typed translations and phonetic suggestions. No suggested correction is applied automatically.

## Verification

```sh
npm run check
npm run build
```

Android verification: build/install the APK, prepare models online, disable networking, record Mandarin, stop/transcribe, translate, save, restart, and reopen the saved session. Editing a copy must clear stale English and save under a new UUID. Deny microphone permission and retry; backgrounding during recording must release the microphone and discard active audio. Reconnect and explicitly publish; retrying the same saved record must not duplicate it. Refresh the web library, compare both texts, run a Qwen summary and custom reanalysis, and reload to confirm saved history.

The production build is written to `build/` and includes the Node server, notes API, and translation-session/analysis APIs:

```sh
npm start
```

Set `HOST`, `PORT`, and `ORIGIN` for the deployment. `ORIGIN` must match the public site origin, including the scheme, so same-origin note and analysis writes work behind the ingress. The production image runs the same SvelteKit API as development.

## Deployment

The production image is `docker.io/czhang19/christina:smallanduseful`. Deploy it to the configured cluster with:

```sh
docker buildx build --platform linux/amd64 -t docker.io/czhang19/christina:smallanduseful --push .
kubectl apply -f k8s/smallanduseful.yaml
kubectl rollout restart deployment/smallanduseful
kubectl rollout status deployment/smallanduseful
```

The manifest connects to the existing persistent MongoDB service at `mongodb.default.svc.cluster.local:27017`. The database is external to the web pod, so notes survive application restarts and redeployments. Deploy the rebuilt image and manifest together; changing only the static assets cannot fix shared notes. MongoDB must remain reachable from the web pod.

Production: <https://smallanduseful.lystic.dev>
