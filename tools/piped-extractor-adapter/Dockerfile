FROM golang:1.27-bookworm@sha256:dc0963f32053e4bc451d24d87e3892d42d01021b61f093e98eb95c0352bb84b0 AS builder
WORKDIR /build
COPY go.mod *.go ./
RUN GOMAXPROCS=1 GOMEMLIMIT=128MiB CGO_ENABLED=0 go test -p 1 ./... && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -p 1 -trimpath -ldflags='-s -w' -o /adapter .
FROM node:22-bookworm-slim@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d AS js
FROM python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df
COPY --from=js /usr/local/bin/node /usr/local/bin/node
COPY --from=js /usr/lib/x86_64-linux-gnu/libstdc++.so.6.0.* /usr/lib/x86_64-linux-gnu/libstdc++.so.6
COPY --from=js /lib/x86_64-linux-gnu/libgcc_s.so.1 /lib/x86_64-linux-gnu/libgcc_s.so.1
RUN python -m pip install --no-cache-dir 'yt-dlp[default,pin]==2026.10.26' && ln -s /usr/local/bin/yt-dlp /opt/yt-dlp
COPY --from=builder /adapter /usr/local/bin/piped-extractor-adapter
USER 1000:1000
ENV PORT=8080 GOMAXPROCS=1 GOMEMLIMIT=32MiB PYTHONDONTWRITEBYTECODE=1
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/piped-extractor-adapter"]
