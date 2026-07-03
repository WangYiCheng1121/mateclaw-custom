# Multimodal

Speech, music, images, video â€?all first-class in GLClaw, not tacked on.

Most AI products treat multimodal generation as a plugin you bolt on later. GLClaw ships with it as core infrastructure: **six image providers, four video providers, three TTS backends, three STT backends, and two music providers**, all unified behind a single tool interface so agents can call any of them without knowing which vendor is underneath.

Configure once. Use everywhere.

---

## What's in the box

### Image generation â€?six providers

| Provider | Model family | Notes |
|----------|--------------|-------|
| **DashScope** | Wanxiang | Alibaba's image model, default cloud option |
| **OpenAI** | DALL-E 3 | Standard DALL-E endpoint |
| **fal.ai** | Flux | Fast Flux inference via fal.ai |
| **Google (Nano Banana)** | gemini-3-pro-image-preview, gemini-2.5-flash-image | Via the native Gemini path; **supports image editing** â€?see [Nano Banana](#nano-banana) below |
| **Zhipu** | CogView | Native Chinese prompt support |
| **MiniMax** | â€?| Sync and async both supported |

The image-generation tool auto-picks the provider configured as default, or you can force a specific one per call. Async generation returns a job id the agent polls; when the image lands, it attaches to the **original assistant message**, not a new one.

::: tip New in 1.3.0
DashScope Wanxiang plugged into the **unified multimodal-generation endpoint** (`multimodal-generation/generation`) in v1.3.0, adding 14 image models â€?6 of which **support image editing**. See [Image edit](#image-edit) below.
:::

#### Image edit

::: tip New in 1.3.0
Image editing (image-to-image) is supported from v1.3.0. In v1.2.0 and earlier, `image_generate` was text-to-image only.
:::

The `image_generate` tool gains two parameters: `image` and `images`:

| Parameter | Shape | Description |
|---|---|---|
| `image` | Single reference image | String: path / `file://` / `data:image/...` / `http(s)://` / `msg:<id>:<idx>` |
| `images` | Multiple reference images (up to 5) | Array of the same forms |

The tool normalizes all five reference forms into in-memory buffers internally before forwarding to the provider. **Five reference forms**:

1. **Local path** â€?`/abs/path.png` / `~/x.png` / `./rel.png`
2. **`file://` URL** â€?absolute-path variant
3. **`data:image/png;base64,...`** â€?inline base64 / percent-encoded body
4. **`http(s)://...`** â€?with SSRF guard (rejects internal hosts)
5. **`msg:<messageId>[:<partIdx>]`** â€?references an image attachment from a message in the same conversation. **Works for non-vision models too** â€?the agent doesn't need to "see" the bytes; merely having seen the messageId in conversation history is enough

```text
User: (uploads a sunset image, messageId=12345) Replace the background with a forest.
Agent: image_generate(prompt="replace background with forest",
                     image="msg:12345:0",
                     model="qwen-image-edit")
```

**Models that support image editing** (DashScope Wanxiang):
- `wan2.7-image` / `wan2.7-image-pro` (**T2I + edit**)
- `qwen-image-edit` / `qwen-image-edit-plus` / `qwen-image-edit-max` (**edit-only**)

A fuller model catalog lives in [Models](./models#two-dashscope-variants).

#### Nano Banana

::: tip New in 1.4.0
Google image generation runs through **Nano Banana Pro** (`gemini-3-pro-image-preview`) via the [native Gemini path](./models#native-gemini), not an OpenAI-compatibility shim.
:::

Because it uses the native `generateContent` endpoint, the image tool passes input images as **inline parts** straight to the model â€?so Nano Banana isn't just text-to-image, it **supports image editing** (image-to-image) too. It works exactly like [Image edit](#image-edit) above: pass the `image` / `images` parameter to reference one or more source images.

- **Nano Banana Pro** â€?`gemini-3-pro-image-preview` (default)
- **Nano Banana** â€?`gemini-2.5-flash-image` (another Google image model)

### Video generation â€?six providers

- **DashScope** â€?Tongyi Wanxiang video
- **Runway** â€?Gen-2 / Gen-3 via API
- **MiniMax (Hailuo)** â€?text-to-video and image-to-video
- **Fal** â€?fast inference pipeline
- **CogVideo** â€?Zhipu CogVideoX
- **Kling** â€?Kuaishou Kling video generation

Same async-attach model as image generation. Videos appear inline in the chat once rendering finishes â€?in the same bubble where the agent first said "working on it".

### Music generation â€?two providers

- **Google Lyria** â€?high-quality music generation
- **MiniMax** â€?music generation with lyrics + style prompts

The music-generation tool takes a prompt, an optional style tag, and optional lyrics. Output is an MP3 attached to the message.

### 3D model generation â€?one provider

- **Tencent Hunyuan 3D** â€?`HY-3D-3.1` / `HY-3D-3.0` (Pro, supports PBR / multi-view / white-model) / `HY-3D-Express` (rapid)

Text-to-3D and image-to-3D both work; output is a `.glb` rendered inline by `<model-viewer>` for drag-to-rotate preview. Full setup walkthrough: **[3D Model Generation](./model3d.md)**.

### Text-to-speech (TTS) â€?three providers

- **DashScope CosyVoice** â€?Chinese + English, natural prosody
- **OpenAI TTS** â€?alloy, echo, fable, onyx, nova, shimmer
- **MiniMax T2A** â€?Chinese voices with emotion tags

Click the speaker icon on any assistant message to read it aloud. The voice is whichever TTS provider is active in Settings.

### Speech-to-text (STT) â€?two providers

- **DashScope Paraformer** â€?Chinese-first, low latency
- **OpenAI Whisper** â€?the standard multilingual benchmark

Hold the mic button in the chat input to speak. Release to transcribe. Edit the result before sending if you want to.

---

## Configuration

All multimodal providers live under `Settings â†?Models â†?[category]`. Add a provider once with its API key, then mark it as default for its category.

```yaml
# application.yml â€?minimal example
mate:
  image:
    default-provider: dashscope
  video:
    default-provider: dashscope
  tts:
    default-provider: cosyvoice
  stt:
    default-provider: paraformer
  music:
    default-provider: dashscope
```

Per-agent overrides are available if you want a specific agent to always use, say, Flux for images and CosyVoice for voice.

---

## How agents use it

Every multimodal capability is exposed as a tool:

| Tool | Signature |
|------|-----------|
| `image_generate` | `(prompt, style?, size?)` |
| `image_edit` | `(image_id, prompt)` â€?where the provider supports it |
| `video_generate` | `(prompt, duration?)` |
| `video_from_image` | `(image_id, prompt)` |
| `music_generate` | `(prompt, style?, lyrics?)` |
| `tts_synthesize` | `(text, voice?)` |
| `stt_transcribe` | `(audio_id, language?)` |

Agents call them exactly like any other tool. The tool layer handles provider selection, retries, async polling, and attachment binding.

---

## Async generation and message binding

Image and video generation often takes longer than a normal agent turn. GLClaw handles this cleanly:

1. Agent calls the generate tool.
2. Tool returns immediately with a job id and a placeholder attachment.
3. Backend polls the provider in the background.
4. When the result lands, it's attached to the **original assistant message** â€?not a new one.

It works the way you'd expect: the image appears inside the same bubble where the agent first said "working on it" â€?not floating in a new message.

---

## Where it shows up in the product

- **Chat** â€?drag an image into the input for vision models; press-and-hold the mic to dictate; click the speaker on any response to read aloud; generated media appears inline.
- **Agents** â€?enable or disable specific multimodal tools per agent.
- **Tools page** â€?every provider has a test button so you can verify a key before using it in production.
- **Desktop app** â€?everything above, plus local filesystem access for batch operations.

---

## When to use what

- **Image** â€?documentation illustrations, slide graphics, concept visualization, marketing. Start with DashScope or Flux; DALL-E 3 when you need tight text rendering.
- **Video** â€?short-form demos, social content, product animations. Runway for quality, MiniMax for Chinese scenarios, DashScope for cloud-local.
- **Music** â€?background tracks, demo jingles, creative exploration. Two providers today; expect the surface to evolve.
- **TTS** â€?accessibility, audiobook-style reading, multilingual content. CosyVoice for Chinese, OpenAI for English variety.
- **STT** â€?voice-first input, meeting transcription, dictation workflows. Paraformer for Chinese, Whisper for everything else.

---

## Multimodal input: primary doesn't speak it? Use a sidecar

::: tip Added in 1.3.0
This page is about **generation (output)**. The **input** side â€?uploading an image to a text-only primary model â€?runs through a separate "multimodal sidecar" path. See [Chat â†?Primary model can't see images?](./chat#primary-model-cant-see-images-multimodal-sidecar-routing) and [Models â†?Multimodal sidecar (system-wide)](./models#multimodal-sidecar-system-wide).
:::

In short: configure a vision model under **Settings â†?Models â†?Multimodal sidecar**. When the primary model can't handle an uploaded image, the runtime captions it via the sidecar first and feeds the description to the primary chat. Primary stays cheap; the routing decision is fully visible in the chat UI (badge on the bubble, hint above the input box).

---

## Next

- [Chat & Messaging](./chat) â€?attachment input, multimodal sidecar routing, how generated media attaches to messages
- [Models](./models) â€?provider configuration UI, multimodal sidecar settings
- [Tools](./tools) â€?the tool system that hosts multimodal generation
