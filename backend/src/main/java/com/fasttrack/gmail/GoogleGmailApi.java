package com.fasttrack.gmail;

import com.fasttrack.config.AppProperties;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/**
 * Gmail access through Google's official client.
 *
 * <p>Only {@code messages.list}, {@code messages.get}, {@code history.list}
 * and {@code getProfile} are ever called - all read-only. The root URL and
 * token endpoint are configurable so the sync can be tested end to end against
 * a stub without a Google account.
 */
@Component
public class GoogleGmailApi implements GmailApi {

    private static final String ME = "me";
    private static final String APP_NAME = "FastTrack";

    private final AppProperties props;

    public GoogleGmailApi(AppProperties props) {
        this.props = props;
    }

    @Override
    public String profileEmailAddress(GmailCredentials credentials) {
        return call(() -> gmail(credentials).users().getProfile(ME).execute().getEmailAddress(),
                "read the mailbox profile");
    }

    @Override
    public String currentHistoryId(GmailCredentials credentials) {
        return call(() -> {
            BigInteger historyId = gmail(credentials).users().getProfile(ME).execute().getHistoryId();
            return historyId == null ? null : historyId.toString();
        }, "read the mailbox history id");
    }

    @Override
    public GmailPage listMessageIds(GmailCredentials credentials, String query, int pageSize, String pageToken) {
        return call(() -> {
            Gmail.Users.Messages.List request = gmail(credentials).users().messages().list(ME)
                    .setQ(query)
                    .setMaxResults((long) pageSize);
            if (pageToken != null && !pageToken.isBlank()) {
                request.setPageToken(pageToken);
            }
            ListMessagesResponse response = request.execute();

            List<String> ids = new ArrayList<>();
            if (response.getMessages() != null) {
                response.getMessages().forEach(message -> ids.add(message.getId()));
            }
            return new GmailPage(ids, response.getNextPageToken());
        }, "list messages");
    }

    @Override
    public List<String> listAddedMessageIdsSince(GmailCredentials credentials, String startHistoryId) {
        return call(() -> {
            Set<String> ids = new LinkedHashSet<>();
            String pageToken = null;
            do {
                Gmail.Users.History.List request = gmail(credentials).users().history().list(ME)
                        .setStartHistoryId(new BigInteger(startHistoryId))
                        .setHistoryTypes(List.of("messageAdded"));
                if (pageToken != null) {
                    request.setPageToken(pageToken);
                }
                ListHistoryResponse response = request.execute();

                if (response.getHistory() != null) {
                    for (History history : response.getHistory()) {
                        if (history.getMessagesAdded() == null) {
                            continue;
                        }
                        for (HistoryMessageAdded added : history.getMessagesAdded()) {
                            if (added.getMessage() != null) {
                                ids.add(added.getMessage().getId());
                            }
                        }
                    }
                }
                pageToken = response.getNextPageToken();
            } while (pageToken != null && !pageToken.isBlank());

            return List.copyOf(ids);
        }, "list history");
    }

    @Override
    public GmailMessage getMessage(GmailCredentials credentials, String messageId) {
        return call(() -> {
            Message message = gmail(credentials).users().messages().get(ME, messageId)
                    .setFormat("full")
                    .execute();
            return toGmailMessage(message);
        }, "read a message");
    }

    // ------------------------------------------------------------------ guts

    private Gmail gmail(GmailCredentials credentials) {
        try {
            UserCredentials userCredentials = UserCredentials.newBuilder()
                    .setClientId(props.getGoogle().getClientId())
                    .setClientSecret(props.getGoogle().getClientSecret())
                    .setRefreshToken(credentials.refreshToken())
                    .setTokenServerUri(URI.create(props.getGmail().getTokenUri()))
                    .build();

            HttpRequestInitializer initializer = new HttpCredentialsAdapter(userCredentials);
            Gmail.Builder builder = new Gmail.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    initializer)
                    .setApplicationName(APP_NAME);

            String rootUrl = props.getGmail().getRootUrl();
            if (rootUrl != null && !rootUrl.isBlank()) {
                builder.setRootUrl(rootUrl.endsWith("/") ? rootUrl : rootUrl + "/");
            }
            return builder.build();
        } catch (GeneralSecurityException | IOException ex) {
            throw new GmailApiException("Could not create a Gmail client", ex, false, false);
        }
    }

    GmailMessage toGmailMessage(Message message) {
        String from = header(message, "From");
        String subject = header(message, "Subject");

        Instant receivedAt = message.getInternalDate() == null
                ? Instant.now()
                : Instant.ofEpochMilli(message.getInternalDate());

        return new GmailMessage(
                message.getId(),
                message.getThreadId(),
                addressOf(from),
                displayNameOf(from),
                subject == null ? "(no subject)" : subject,
                message.getSnippet() == null ? "" : message.getSnippet(),
                extractBody(message.getPayload()),
                receivedAt);
    }

    private String header(Message message, String name) {
        if (message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return null;
        }
        for (MessagePartHeader header : message.getPayload().getHeaders()) {
            if (name.equalsIgnoreCase(header.getName())) {
                return header.getValue();
            }
        }
        return null;
    }

    /** "Stripe Recruiting &lt;no-reply@stripe.com&gt;" -&gt; the address. */
    static String addressOf(String fromHeader) {
        if (fromHeader == null || fromHeader.isBlank()) {
            return "unknown@unknown";
        }
        int open = fromHeader.lastIndexOf('<');
        int close = fromHeader.lastIndexOf('>');
        if (open >= 0 && close > open) {
            return fromHeader.substring(open + 1, close).trim().toLowerCase();
        }
        return fromHeader.trim().toLowerCase();
    }

    /** "Stripe Recruiting &lt;no-reply@stripe.com&gt;" -&gt; the display name. */
    static String displayNameOf(String fromHeader) {
        if (fromHeader == null || fromHeader.isBlank()) {
            return null;
        }
        int open = fromHeader.lastIndexOf('<');
        if (open <= 0) {
            return null;
        }
        String name = fromHeader.substring(0, open).trim();
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
            name = name.substring(1, name.length() - 1);
        }
        return name.isBlank() ? null : name;
    }

    /**
     * Walks the MIME tree for text. Plain text wins; HTML is converted with
     * Jsoup, which also neutralises any script or tracking markup before the
     * text reaches a prompt.
     */
    String extractBody(MessagePart part) {
        if (part == null) {
            return "";
        }
        String plain = findPart(part, "text/plain");
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        String html = findPart(part, "text/html");
        if (html != null && !html.isBlank()) {
            return Jsoup.parse(html).text();
        }
        return "";
    }

    private String findPart(MessagePart part, String mimeType) {
        if (mimeType.equalsIgnoreCase(part.getMimeType())
                && part.getBody() != null && part.getBody().getData() != null) {
            return decode(part.getBody().getData());
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                String found = findPart(child, mimeType);
                if (found != null && !found.isBlank()) {
                    return found;
                }
            }
        }
        return null;
    }

    private String decode(String base64Url) {
        try {
            return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private interface GmailCall<T> {
        T execute() throws IOException;
    }

    /** Translates Google's exceptions into something the sync can reason about. */
    private <T> T call(GmailCall<T> call, String what) {
        try {
            return call.execute();
        } catch (GoogleJsonResponseException ex) {
            int status = ex.getStatusCode();
            boolean auth = status == 401 || status == 403;
            boolean retryable = status == 429 || status >= 500;
            throw new GmailApiException(
                    "Gmail refused to " + what + " (HTTP " + status + ")", ex, retryable, auth);
        } catch (IOException ex) {
            // Includes token-refresh failures, which mean the grant is gone.
            boolean auth = ex.getMessage() != null
                    && (ex.getMessage().contains("invalid_grant") || ex.getMessage().contains("unauthorized"));
            throw new GmailApiException("Gmail could not " + what, ex, !auth, auth);
        }
    }
}
