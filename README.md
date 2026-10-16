# SellGUI

Paper/Spigot 1.20+ plugin. `/sell` opens a double chest; put items in and click the green button in the last slot to sell them.
Sales show `+$<amount>` in chat (green `+$`, white amount).

- Requires **Vault** and an economy plugin
- Edit prices in `plugins/SellGUI/prices.yml`, then `/sell reload`
- Permissions: `sellgui.use`, `sellgui.reload`

## Build
`mvn package` -> `target/SellGUI-1.0.0.jar`
(or push to GitHub and download the jar from the Actions tab)
