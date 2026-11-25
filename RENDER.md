# UniStudy 5.7 on Render

The Docker image builds the Java server and serves its current HTML/CSS/JavaScript client from the same origin. The client is synchronized with the local 5.7 web version and uses the Android navy, mint and light background palette.

The proposed Blueprint runs one 512 MB web service in Frankfurt with a 1 GB persistent disk mounted at `/var/data`. SQLite uses `/var/data/unistudy.db`; uploads use `/var/data/files`. The health endpoint is `/api/health`. Automatic Blueprint sync and service deploys should remain disabled until data migration is complete.

This configuration requires a paid service and persistent disk. Review the Render plan before applying it. Do not run the public-password local demo initializer on the hosted service.

## Initial data transfer

The public repository intentionally contains no real database, account passwords, sessions or uploaded course files. Before inviting users, transfer a consistent SQLite backup and the referenced upload files to the persistent disk over an authenticated connection. Never commit these files or put them in the Docker build context. Validate the transferred database and file paths, then verify sign-in and file downloads. A newly started server without this transfer has an empty database.

Use the resulting HTTPS origin in the Android server settings. The existing local server and APK can continue to use their local address independently.
