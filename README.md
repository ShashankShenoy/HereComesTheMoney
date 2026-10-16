# Moneybags Oracle ALPHA

Use PowerShell 7 in `Downloads/integrated`. Connect the ALPHA database network/VPN and have the team bundle passphrase ready.

Only three user-facing scripts remain:

```powershell
./set-openrouter-key.ps1  # Save or replace your OpenRouter API key.
./start-alpha-team.ps1    # Build and start against the ALPHA Oracle database.
./stop-oracle-alpha.ps1   # Stop the application services.
```

Open [the application](http://localhost:5174). Requires Java JDK 17+, Maven 3.9+, Node.js 20+, and PowerShell 7. The start script prompts privately for the encrypted ALPHA bundle passphrase, automatically displays all seven team logins with roles, passwords, and any customer access keys, then launches six services, the gateway, and frontend. Oracle data persists after stopping.

Use `./start-alpha-team.ps1 -BuildOnly` to build before connecting a restrictive VPN, `-Offline` for cached dependencies, or `-SkipBuild` to start a verified build. Use `-ShowCredentials` to view locally unlocked team logins privately. Internal helpers and developer checks live under `Downloads/integrated/tools`.

See [the product guide](Downloads/integrated/README.md) and [service architecture](Downloads/integrated/docs/MICROSERVICES.md). H2 and alternate startup launchers have been removed.
