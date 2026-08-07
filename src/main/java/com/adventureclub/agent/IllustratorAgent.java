package com.adventureclub.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * Pictures are generated with <a href="https://developers.cloudflare.com/workers-ai/">Cloudflare
 * Workers AI</a> through its REST API
 * ({@code POST https://api.cloudflare.com/client/v4/accounts/{account-id}/ai/run/{model}}),
 * authenticated with an API token sent as an {@code Authorization: Bearer <CLOUDFLARE_API_TOKEN>}
 * header. The FLUX.2 [klein] model unifies text-to-image generation AND image-to-image editing
 * in a single model, so ONE model id serves both jobs. Inputs travel as
 * {@code multipart/form-data} (a {@code prompt} text part plus up to four binary
 * {@code input_image_0..3} parts); the rendered picture comes back as a base64 string in
 * {@code result.image}, ready to hand straight to the browser as a {@code data:} URL.
 * <p>
 * <b>Draw from scratch (no base picture yet).</b> A fresh picture is drawn from the story
 * text alone with the model {@code adventureclub.images.model} (default
 * {@code @cf/black-forest-labs/flux-2-klein-4b}). We send a child-friendly {@code prompt} built
 * from the story text and the child's interests.
 * <p>
 * <b>Image-to-image editing (a base picture exists).</b> When a base picture exists — a
 * drawing the child just uploaded, else the previous picture the client sends back — we paint
 * the new story beat <i>onto that picture</i> with the same model
 * ({@code adventureclub.images.edit-model}, also default
 * {@code @cf/black-forest-labs/flux-2-klein-4b}). The base picture(s) travel as binary
 * {@code input_image_0}, {@code input_image_1}, … multipart parts; when the child just uploaded
 * a new drawing it is passed alongside the current scene so the model merges them instead of the
 * upload replacing the scene. Note Workers AI expects input images smaller than 512x512.
 * <p>
 * Everything is env-driven (base-url + account id + models + token), so switching
 * model/provider is a config change.
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

    // Prompt for merging a freshly uploaded drawing INTO the existing picture. Image 0 is the
    // ongoing adventure picture; image 1 is a new drawing the child just added — we blend its
    // characters/objects into the ongoing scene rather than replace it.
    private static final String COMBINE_PROMPT = """
            Image 0 is the ongoing adventure picture. Image 1 is a new \
            drawing the child just made. Combine them: keep image 0's setting and \
            style, composition and colours EXACTLY as they are — the result must clearly \
            look like the SAME image 0 — and add ONLY the characters and objects from \
            image 1 INTO that same scene very NEATLY. \
            Image 0 is a main base picture. Image 1 contains the element that you need to add to image 0.\
            The only new elements on the image should be the elements from image 1 and child request.\
            Do NOT replace, redraw or reimagine image 0; change nothing that is \
            already there, just add image 1. Keep it a warm, whimsical, brightly coloured storybook \
            illustration for a child aged 6-12. Never scary, dark or violent. The child loves: %s. Child request is %s. \
            Not use any text, letters, words, numbers, captions, speech bubbles, labels, \
            signs or writing of any kind anywhere in the image.""";

    private final RestClient cloudflareClient;   // Cloudflare Workers AI REST API
    private final String accountId;               // Cloudflare account id (path segment)
    private final String model;                   // text-to-image model id
    private final String editModel;               // image-to-image (editing) model id
    private final String apiKey;
    private final boolean enabled;

    public IllustratorAgent(
            @Value("${adventureclub.images.enabled:true}") boolean enabled,
            @Value("${adventureclub.images.base-url:https://api.cloudflare.com/client/v4}") String baseUrl,
            @Value("${adventureclub.images.account-id:}") String accountId,
            @Value("${adventureclub.images.model:@cf/black-forest-labs/flux-2-klein-4b}") String model,
            @Value("${adventureclub.images.edit-model:@cf/black-forest-labs/flux-2-klein-4b}") String editModel,
            @Value("${adventureclub.images.api-key:}") String apiKey,
            RestClient.Builder restClientBuilder) {
        this.enabled = enabled;
        this.accountId = accountId;
        this.model = model;
        this.editModel = editModel;
        this.apiKey = apiKey;
        this.cloudflareClient = restClientBuilder.clone().baseUrl(baseUrl).build();
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
     * Edits the base picture so the adventure keeps growing on one canvas. The base picture(s)
     * travel as binary {@code input_image_0}, {@code input_image_1}, … multipart parts; when
     * the child just uploaded a new drawing it is passed alongside the current scene so the
     * model merges them instead of the upload replacing the scene.
     */
    private String editPicture(String interests, String storyBeat, List<SourceImage> images) {
        log.info("editPicture by interests: {} and story: {} and images size: {}", interests, storyBeat, images.size());
        // With more than one picture we blend the new upload into the scene; otherwise we
        // simply continue the single base picture.
        String prompt = images.size() > 1
                ? COMBINE_PROMPT.formatted(interests, storyBeat)
                : EDIT_PROMPT.formatted(interests, storyBeat);
        log.info("editPicture prompt: {}", prompt);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("prompt", prompt);
        // Workers AI (FLUX.2) supports up to 4 input images, named input_image_0..3.
        int max = Math.min(images.size(), 4);
        for (int i = 0; i < max; i++) {
            SourceImage img = images.get(i);
            String mime = (img.mediaType() == null || img.mediaType().isBlank())
                    ? "image/png" : img.mediaType();
            byte[] bytes = Base64.getDecoder().decode(img.data());
            String filename = "input_image_" + i + "." + mime.substring(mime.indexOf('/') + 1);
            body.add("input_image_" + i, new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
        }

        String url = callCloudflare(editModel, body);
        if (url == null) {
            log.warn("Cloudflare Workers AI edit returned no picture — continuing without one this turn");
            return null;
        }
        log.debug("Illustration edited via Cloudflare Workers AI");
        return url;
    }

    /** Draws a fresh picture from the story text alone via the text-to-image model. */
    private String drawPicture(String interests, String storyBeat) {
        log.info("drawPicture by interests: {} and story: {}", interests, storyBeat);
        String prompt = PROMPT.formatted(interests, storyBeat);
        log.info("drawPicture prompt: {}", prompt);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("prompt", prompt);

        String url = callCloudflare(model, body);
        if (url == null) {
            log.warn("Cloudflare Workers AI returned no picture — continuing without one this turn");
            return null;
        }
        log.debug("Illustration created via Cloudflare Workers AI");
        return url;
    }

    /**
     * Calls a Cloudflare Workers AI model
     * ({@code POST https://api.cloudflare.com/client/v4/accounts/{account-id}/ai/run/{model}})
     * with a {@code multipart/form-data} body and returns the rendered picture as a
     * {@code data:} URL, or {@code null} when nothing came back. Workers AI returns the picture
     * as a base64 string in {@code result.image}.
     */
    private String callCloudflare(String model, MultiValueMap<String, Object> body) {
        // Pass the model path literally (no URI template) so its slashes and the leading
        // "@cf/..." are kept, not percent-encoded.
        Map<?, ?> response = cloudflareClient.post()
                .uri("/accounts/" + accountId + "/ai/run/" + model)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(this::applyAuth)
                .body(body)
                .retrieve()
                .body(Map.class);
        return extractImage(response);
    }

    /** Adds the {@code Authorization: Bearer <CLOUDFLARE_API_TOKEN>} header when a token is configured. */
    private void applyAuth(HttpHeaders headers) {
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
    }

    /**
     * Pulls the base64 picture out of a Cloudflare Workers AI response ({@code result.image})
     * and wraps it as a PNG {@code data:} URL, or returns null when nothing came back.
     */
    private static String extractImage(Map<?, ?> response) {
        if (response == null) {
            return null;
        }
        Object result = response.get("result");
        if (result instanceof Map<?, ?> map) {
            Object image = map.get("image");
            String value = image != null ? image.toString() : null;
            if (value != null && !value.isBlank()) {
                return "data:image/png;base64," + value;
            }
        }
        return null;
    }
}
