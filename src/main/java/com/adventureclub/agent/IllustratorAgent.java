package com.adventureclub.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Illustrator — grows ONE living picture of the adventure for each safe story beat.
 * <p>
 * Pictures are generated with <a href="https://pollinations.ai">Pollinations AI</a>.
 * <p>
 * <b>Draw from scratch (no base picture yet).</b> A fresh picture is drawn from the story
 * text alone via the free, keyless GET endpoint
 * ({@code https://image.pollinations.ai/prompt/{prompt}}), which returns the rendered image
 * bytes directly. We URL-encode a child-friendly prompt built from the story text and the
 * child's interests, fetch the image and hand it back as a browser-ready {@code data:} URL.
 * <p>
 * <b>Image-to-image editing (a base picture exists).</b> When a base picture exists — a
 * drawing the child just uploaded, else the previous picture the client sends back — we paint
 * the new story beat <i>onto that picture</i>. The base picture is sent in the request
 * <i>body</i> (multipart/form-data) to the OpenAI-compatible edit endpoint
 * ({@code POST https://gen.pollinations.ai/v1/images/edits}) using an image-editing model
 * ({@code adventureclub.images.edit-model}, default {@code kontext}). Sending the picture in
 * the body — rather than as a {@code data:} URI in the query string — is what avoids the
 * {@code 414 URI Too Long} error the old query-param approach hit on large images. The edit
 * endpoint requires an account token, supplied via {@code adventureclub.images.api-key} (env
 * {@code POLLINATIONS_API_KEY}) and sent as a {@code Bearer} token.
 * <p>
 * Everything is env-driven (base-urls + models + size + key), so switching model/provider is
 * a config change.
 * <p>
 * Best-effort: any failure (network, rate limit, missing token, empty body) is swallowed and
 * {@code null} is returned — the story still reaches the child, just without a new picture
 * this turn. Set {@code IMAGES_ENABLED=false} to turn pictures off entirely.
 */
@Component
public class IllustratorAgent {

    private static final Logger log = LoggerFactory.getLogger(IllustratorAgent.class);

    // Prompt for drawing a fresh picture from text alone (no base picture yet).
    // Strong, repeated "no text" instruction because the models tend to render the story
    // words INTO the picture otherwise; the story beat is a description of the SCENE to
    // draw, never a caption to write on the image.
    private static final String PROMPT = """
            A warm, whimsical, brightly coloured storybook illustration for a child \
            aged 6-12, in a soft hand-drawn picture style. Never scary, dark or \
            violent. The child loves: %s. Base the new generated image on the interest \
            and the request provided. The child request is %s.\
            Do not use text, letters, words, numbers, captions, \
            speech bubbles, labels, signs or writing of any kind anywhere in the image.""";

    // Prompt for editing the existing picture so the adventure grows on one canvas.
    // Heavy emphasis on STAYING CLOSE to the provided picture: the same models otherwise
    // tend to redraw the whole scene from scratch, so the result looks like a different
    // picture. We ask for a minimal edit that preserves the original composition, colours,
    // characters and layout, changing only what the new beat strictly requires.
    private static final String EDIT_PROMPT = """
            The generated picture must clearly look like \
            the SAME picture, only SLIGHTLY tuned. Preserve its exact composition, \
            layout, colours, characters, background and hand-drawn childish style — do NOT redraw \
            or reimagine the scene, do NOT move or restyle what is already there. Do NOT add new elements.\
            Keep the same warm, whimsical, brightly coloured storybook illustration for a child \
            aged 6-12. Never scary, dark or violent. The child loves: %s. Gently add ONLY \
            this new moment of the adventure into the existing scene.\
            Carfully add to the picture what the child requests without the changing the picture. \
            Child request is %s. Not use any text, letters, words, numbers, captions, speech \
            bubbles, labels, signs or writing of any kind anywhere in the image.""";

    // Prompt for merging a freshly uploaded drawing INTO the existing picture. The first
    // image is the ongoing adventure picture; the second is a new drawing the child just
    // added — we blend its characters/objects into the ongoing scene rather than replace it.
    private static final String COMBINE_PROMPT = """
            The first image is the ongoing adventure picture. The second image is a new \
            drawing the child just made. Combine them: keep the first picture's setting and \
            style, composition and colours EXACTLY as they are — the result must clearly \
            look like the SAME first picture — and add ONLY the characters and objects from \
            the second drawing INTO that same scene very NEATLY. \
            First image is a main base picture. The second image contain the element that you need to add to the first image.\
            The only new elements on the image should be the elements from the second picture and child request.\
            Do NOT replace, redraw or reimagine the first picture; change nothing that is \
            already there, just add the second image. Keep it a warm, whimsical, brightly coloured storybook \
            illustration for a child aged 6-12. Never scary, dark or violent. The child loves: %s. Child request is %s. \
            Not use any text, letters, words, numbers, captions, speech bubbles, labels, \
            signs or writing of any kind anywhere in the image.""";

    private final RestClient drawClient;   // keyless text-to-image GET (image.pollinations.ai)
    private final RestClient editClient;   // OpenAI-compatible edits POST (gen.pollinations.ai)
    private final String model;
    private final String editModel;
    private final String apiKey;
    private final int width;
    private final int height;
    private final boolean enabled;

    public IllustratorAgent(
            @Value("${adventureclub.images.enabled:true}") boolean enabled,
            @Value("${adventureclub.images.base-url:https://image.pollinations.ai}") String baseUrl,
            @Value("${adventureclub.images.edit-base-url:https://gen.pollinations.ai}") String editBaseUrl,
            @Value("${adventureclub.images.model:flux}") String model,
            @Value("${adventureclub.images.edit-model:kontext}") String editModel,
            @Value("${adventureclub.images.api-key:}") String apiKey,
            @Value("${adventureclub.images.width:768}") int width,
            @Value("${adventureclub.images.height:768}") int height,
            RestClient.Builder restClientBuilder) {
        this.enabled = enabled;
        this.model = model;
        this.editModel = editModel;
        this.apiKey = apiKey;
        this.width = width;
        this.height = height;
        this.drawClient = restClientBuilder.clone().baseUrl(baseUrl).build();
        this.editClient = restClientBuilder.clone().baseUrl(editBaseUrl).build();
    }

    /**
     * Draws the picture for the current story beat.
     * <p>
     * If any {@code baseImages} are present the new beat is painted onto them (image-to-image
     * editing) so the adventure grows on one living canvas; otherwise a fresh picture is
     * drawn from the story text alone.
     *
     * @param interests the child's interests, used to theme the picture
     * @param baseImages the picture(s) to build on — typically the current scene and/or a
     *                   fresh upload the child just added; when more than one is present they
     *                   are merged (upload blended INTO the scene), when empty a fresh picture
     *                   is drawn from the story text alone; may be null/empty
     * @return a browser-ready {@code data:} URL for the picture, or {@code null} when
     *         illustration is disabled or failed
     */
    public String illustrate(String interests, String childMessage,
                             List<SourceImage> baseImages) {
        if (!enabled || childMessage == null || childMessage.isBlank()) {
            return null;
        }

        String themedInterests = interests == null || interests.isBlank() ? "adventures" : interests;
        List<SourceImage> images = baseImages == null ? List.of() : baseImages.stream()
                .filter(i -> i != null && i.data() != null && !i.data().isBlank())
                .toList();

        try {
            return images.isEmpty()
                    ? drawPicture(themedInterests, childMessage)
                    : editPicture(themedInterests, childMessage, images);
        } catch (Exception e) {
            // Best-effort: never fail a turn because a picture could not be drawn.
            log.warn("Illustration generation failed — continuing without a picture: {}", e.getMessage());
            return null;
        }
    }

    /** A source picture to build the illustration on: raw base64 (no {@code data:} prefix) + MIME type. */
    public record SourceImage(String data, String mediaType) {
    }

    /**
     * Edits the base picture so the adventure keeps growing on one canvas. The base image
     * travels in the multipart <b>body</b> (not the URL), which is what prevents the
     * {@code 414 URI Too Long} error large pictures triggered with the old query-param path.
     */
    private String editPicture(String interests, String storyBeat, List<SourceImage> images) {
        log.info("editPicture by interests: {} and story: {} and images size: {}", interests, storyBeat, images.size());
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        // Add every source picture as its own multipart `image` part. When the child just
        // uploaded a new drawing it arrives alongside the current scene, so BOTH travel to
        // Pollinations — the model merges them instead of the upload replacing the scene.
        for (int i = 0; i < images.size(); i++) {
            SourceImage img = images.get(i);
            String mime = (img.mediaType() == null || img.mediaType().isBlank())
                    ? "image/png" : img.mediaType();
            byte[] bytes = Base64.getDecoder().decode(img.data());
            String filename = "source" + i + "." + mime.substring(mime.indexOf('/') + 1);
            body.add("image", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    log.info("Image filename: {}", filename);
                    return filename;
                }
            });
        }
        // With more than one picture we blend the new upload into the scene; otherwise we
        // simply continue the single base picture.
        String prompt = images.size() > 1
                ? COMBINE_PROMPT.formatted(interests, storyBeat)
                : EDIT_PROMPT.formatted(interests, storyBeat);
        log.info("editPicture prompt: {}", prompt);
        body.add("prompt", prompt);
        body.add("model", editModel);
        body.add("size", width + "x" + height);
        body.add("response_format", "b64_json");

        Map<?, ?> response = editClient.post()
                .uri("/v1/images/edits")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(this::applyAuth)
                .body(body)
                .retrieve()
                .body(Map.class);

        String b64 = extractB64Json(response);
        if (b64 == null || b64.isBlank()) {
            log.warn("Pollinations edit returned no picture — continuing without one this turn");
            return null;
        }
        log.debug("Illustration edited via Pollinations ({} base64 chars)", b64.length());
        // b64_json has no MIME; Pollinations returns PNG for edits.
        return "data:image/png;base64," + b64;
    }

    /** Draws a fresh picture from the story text alone via the keyless GET endpoint. */
    private String drawPicture(String interests, String storyBeat) {
        log.info("drawPicture by interests: {} and story: {}", interests, storyBeat);
        String prompt = PROMPT.formatted(interests, storyBeat);
        log.info("drawPicture prompt: {}", prompt);
        ResponseEntity<byte[]> response = drawClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/prompt/{prompt}")
                        .queryParam("model", model)
                        .queryParam("width", width)
                        .queryParam("height", height)
                        .queryParam("nologo", true)
                        .queryParam("safe", true)
                        .build(prompt))
                .retrieve()
                .toEntity(byte[].class);

        byte[] image = response.getBody();
        if (image == null || image.length == 0) {
            log.warn("Pollinations returned no picture — continuing without one this turn");
            return null;
        }

        MediaType contentType = response.getHeaders().getContentType();
        String mime = contentType != null ? contentType.toString() : "image/jpeg";
        log.debug("Illustration created via Pollinations ({} bytes)", image.length);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);
    }

    /** Adds the Bearer token (required by the edit endpoint) when an API key is configured. */
    private void applyAuth(HttpHeaders headers) {
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
    }

    /** Pulls {@code data[0].b64_json} out of an OpenAI-compatible image response, or null. */
    private static String extractB64Json(Map<?, ?> response) {
        if (response == null) {
            return null;
        }
        Object data = response.get("data");
        if (data instanceof List<?> list && !list.isEmpty()
                && list.getFirst() instanceof Map<?, ?> first) {
            Object b64 = first.get("b64_json");
            return b64 != null ? b64.toString() : null;
        }
        return null;
    }
}
