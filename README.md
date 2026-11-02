## Minecraft Transit Railway 4.0.0

_Minecraft Transit Railway_ is a [Minecraft mod](https://minecraft.wiki/w/Mods) that allows you to build your own transport network with automated trains, boats, cable cars, and planes!

Version 4.0.0 is a complete rewrite from scratch. The vehicle simulation code has been separated from the Minecraft instance, greatly reducing lag on Minecraft servers.

## Major Features in 4.0.0

- Custom train configuration
- Realistic bogies and train movement
- Improved signalling
- Sideways elevators
- OBJ features ported over from Nemo's Transit Expansion
- Vehicle swaying effect
- New online system map and OneBusAway integration
- Client-side FPS improvements and server-side TPS improvements

[See these features in detail.](https://www.youtube.com/playlist?list=PLp0jZgheSlXuQCXu9UynrKuBnq2Ef_TSI)

[![Video Trailer](https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/video-preview.png)](https://www.youtube.com/watch?v=1cZfU7t4cAk)

Please report any issues or bugs you find; that would be greatly appreciated! Refer to the [todo list](https://github.com/jonafanho/Minecraft-Transit-Railway/projects/2) to see currently known issues.

## Euro Economy fares (this fork)

Ticket machines no longer take emeralds. Fares are paid from a **transit card** that players load with Euro from the [Euro Economy](https://github.com/Dyplay/money-mod-v2) bank (its `neoforge-mod`):

- **Ticket machine:** shows the card and bank balances. "Add Value" loads €1 to €500 from the bank onto the card; "Refund" pays what is left on the card back into the bank.
- **Ticket barriers and processors:** work as before, charging the card in Euro. Entering needs a card balance of €0 or more; a journey the card cannot cover leaves it negative until it is topped up.
- **Fares** are set in `config/mtr.json` under `server`, in Euro cents: `baseFareCents` (default `200`, €2), `zoneFareCents` per zone crossed (default `100`, €1) and `fareEvasionFineCents` (default `50000`, €500). Creative-mode players pay half.
- The card balance is kept in the scoreboard objective `mtr_card_cents`. Old emerald-bought balances (`mtr_balance`) are not carried over, since they were never paid for in Euro.

MTR finds the economy at runtime (`org.mtr.mod.data.EuroEconomyBridge`, by reflection on `com.dyplay.euroeconomy.neoforge.api.EuroEconomyApi`), so it still loads without it - ticket machines then say the bank is unavailable. The economy only runs on a dedicated server, so in singleplayer the ticket machines are unavailable too. Top-ups and refunds are filed in the Euro Economy ledger as `TRANSIT_CARD_TOPUP` and `TRANSIT_CARD_REFUND`.

The Euro Economy mod targets NeoForge 1.21.1, while this code base currently builds for Minecraft 1.16.5-1.20.4 (Fabric and Forge), so the two only run together once MTR is ported to NeoForge 1.21.1.

## Downloads and Installation

Head over to the [Modrinth page](https://modrinth.com/mod/minecraft-transit-railway) to download the mod or to see the project information.

## Guide

There is a [new wiki](https://wiki.minecrafttransitrailway.com/start) for the mod. Take a look.

## Recommended Addons

- [MTR London Underground Addon](https://modrinth.com/mod/the-tube)
- [Joban Client Mod](https://modrinth.com/mod/jcm)
- [Station Decoration](https://modrinth.com/mod/station-decoration)
- [Russian Metro Addon](https://modrinth.com/mod/russian-metro-addon)

## Contributing

### Help Translate the Mod!

The [Crowdin site for the Minecraft Transit Railway mod](https://crwd.in/minecraft-transit-railway) is available!

Crowdin is a cloud-based platform for translators to contribute to a project. With your help, we can translate the mod into many different languages. You may create a free account to start translating.

[![Crowdin](https://badges.crowdin.net/minecraft-transit-railway/localized.svg)](https://crowdin.com/project/minecraft-transit-railway)

### Adding Features

1. Fork this project
2. On your fork, create a new branch based on the development version branch
3. Commit your changes to the new branch
4. Make a Pull Request to merge your branch into the development version of this repository

### Building

To build the mod, run the following commands in the root directory of the project:

```gradle
gradlew setupFiles -PminecraftVersion="<minecraft version>"
gradlew build -PminecraftVersion="<minecraft version>"
```

The mod jar file should be generated in the following directory:

```
<root>/build/release/MTR-<fabric|forge>-<mod version>+<minecraft version>.jar
```

## License

This project is licensed under the [MIT License](https://opensource.org/licenses/MIT). All [Noto fonts](http://www.google.com/get/noto/), bundled with this mod, are licensed under the [Open Font License](http://scripts.sil.org/OFL).

## Questions? Comments? Complaints?

Let's connect.

<a href="https://discord.gg/PVZ2nfUaTW" target="_blank"><img src="https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/discord.png" alt="Discord" width=64></a>
&nbsp;
<a href="https://www.linkedin.com/in/jonathanho33" target="_blank"><img src="https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/linked_in.png" alt="LinkedIn" width=64></a>
&nbsp;
<a href="mailto:jonho.minecraft@gmail.com" target="_blank"><img src="https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/email.png" alt="Email" width=64></a>
&nbsp;
<a href="https://www.patreon.com/minecraft_transit_railway" target="_blank"><img src="https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/patreon.png" alt="Patreon" width=64></a>
