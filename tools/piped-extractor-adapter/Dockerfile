FROM node:22-bookworm-slim@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d
# This verified upstream standalone executable is Linux amd64 only.
ADD --checksum=sha256:767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795 https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp_linux /opt/yt-dlp
RUN chmod 0555 /opt/yt-dlp
WORKDIR /app
COPY --chown=node:node media.mjs server.mjs ./
USER node
ENV NODE_ENV=production PORT=8080
EXPOSE 8080
CMD ["node", "server.mjs"]
