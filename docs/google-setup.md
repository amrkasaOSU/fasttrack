# Connecting Gmail (free, ~10 minutes)

FastTrack reads your job-application email with **read-only** Gmail access. To
do that it needs an OAuth client that belongs to you. Google charges nothing
for this, and an app left in **Testing** mode needs no verification review.

You will end up with two values - a client ID and a client secret - which go
into your local `.env` file. **Nothing here is committed to git**, and nobody
else ever needs to see them.

---

## 1. Create a Google Cloud project

1. Open <https://console.cloud.google.com/>.
2. Click the project dropdown at the top, then **New project**.
3. Name it `FastTrack` (any name works). Leave the organisation as-is.
4. Click **Create**, then make sure the new project is selected in the
   dropdown before continuing.

## 2. Enable the Gmail API

1. Go to **APIs & Services → Library**, or open
   <https://console.cloud.google.com/apis/library/gmail.googleapis.com>.
2. Search for **Gmail API**, open it, and click **Enable**.

## 3. Configure the consent screen

1. Go to **APIs & Services → OAuth consent screen**.
2. Choose **External** and click **Create**. (Internal is only available with a
   Workspace organisation; External in Testing mode is the free path.)
3. Fill in the required fields:
   - **App name:** FastTrack
   - **User support email:** your own address
   - **Developer contact email:** your own address
   Everything else can stay empty.
4. Click **Save and continue**.
5. On the **Scopes** step, click **Add or remove scopes**, then add exactly:

   ```
   openid
   .../auth/userinfo.email
   .../auth/userinfo.profile
   https://www.googleapis.com/auth/gmail.readonly
   ```

   `gmail.readonly` is a restricted scope. You will see a warning that
   restricted scopes normally require verification - that warning does not
   apply while the app stays in Testing with test users, which is all this
   setup needs. Click **Update**, then **Save and continue**.
6. On **Test users**, click **Add users** and enter the Gmail address you want
   FastTrack to read. You can add up to 100. Click **Save and continue**.
7. Leave the publishing status as **Testing**. Do not click "Publish app" -
   that is what would trigger a verification review.

> **What Testing mode means in practice:** only the addresses you listed can
> sign in, and Google will show an "unverified app" screen the first time
> (click *Advanced → Go to FastTrack*). Refresh tokens issued to a Testing app
> expire after 7 days, so you will be asked to reconnect Gmail about once a
> week. FastTrack handles that by reporting the connection as expired and
> offering a Reconnect button - it never silently stops syncing.

## 4. Create the OAuth client

1. Go to **APIs & Services → Credentials**.
2. Click **Create credentials → OAuth client ID**.
3. **Application type:** Web application.
4. **Name:** FastTrack local.
5. Under **Authorised redirect URIs**, click **Add URI** and enter exactly:

   ```
   http://localhost:8080/login/oauth2/code/google
   ```

   If you run the backend on a different port (see `BACKEND_PORT` in `.env`),
   use that port instead - for example
   `http://localhost:8222/login/oauth2/code/google`.
   When you deploy, add the deployed URL the same way:
   `https://your-domain/login/oauth2/code/google`.
6. Click **Create**. Google shows your **Client ID** and **Client secret**.

## 5. Put the values in your .env

In the project root:

```bash
cp .env.example .env    # if you have not already
```

Then edit `.env` and set:

```bash
GOOGLE_CLIENT_ID=<the client ID Google showed you>
GOOGLE_CLIENT_SECRET=<the client secret Google showed you>
```

While you are in the file, generate the key that encrypts your stored refresh
token (AES-256-GCM, 32 bytes, base64):

```bash
openssl rand -base64 32
```

```bash
TOKEN_ENCRYPTION_KEY=<the output of that command>
```

Without `TOKEN_ENCRYPTION_KEY`, FastTrack refuses to store a token at all and
reports Gmail as not configured - deliberately, so a token can never be
written in plaintext.

## 6. Restart and connect

```bash
docker compose up -d --build backend frontend
```

Open the app, go to **Settings → Connect Gmail**, and complete the Google
consent screen. The permission list should show only "Read your email
messages and settings". If it shows anything that can send or delete mail,
stop - something is wrong with the scope configuration.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `redirect_uri_mismatch` | The URI in Google Cloud does not match the port the backend runs on | Add the exact URI, including the port, under Credentials → your client → Authorised redirect URIs |
| "Access blocked: this app is not verified" | Expected in Testing mode | Click **Advanced → Go to FastTrack (unsafe)**. It is your own app |
| "FastTrack has not completed the Google verification process" | Same as above | Same as above |
| You are asked to reconnect every week | Testing-mode refresh tokens expire after 7 days | Expected. Click Reconnect, or publish the app if you later want it to persist |
| `invalid_client` | Client ID or secret mis-copied | Re-copy both from Credentials; check for a trailing space in `.env` |
| The app says "Gmail not configured" | A value is missing | All three of `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and `TOKEN_ENCRYPTION_KEY` must be set |

## What FastTrack can and cannot do with this access

- **Can:** list message ids, read messages it lists, read your email address
  and name.
- **Cannot:** send, reply, forward, delete, archive, label, or modify anything.
  The `gmail.readonly` scope does not permit it, and no code in this project
  calls a write endpoint. The scope list is asserted by an automated test.

Message bodies are processed in memory and never stored - see
[privacy.md](privacy.md).
