StrengthSMP 1.2.0 - Paper 26.1.2

RULES
  Strength 1 = 1.0x, 2 = 1.4x, 3 = 2.0x, 4 = 3.0x, 5 = 4.0x damage against players.
  Everyone starts at 3. Min 1, max 5. Kills: +1 for killer, -1 for victim.
  /withdraw [amount] turns Strength into Strength Crystals (right-click to use). You always keep at least 1.

ASSIGNED ITEM
  The first time a player joins they are randomly assigned one of: Trident, Sword, Mace, Armor, Axe, Bow, Crossbow.
  They are only told about it (nothing is given). /strength shows it. Saved in plugins/StrengthSMP/config.yml.
  Players assigned Armor: diamond armor turns into netherite (enchants and trims kept) while worn,
  and turns back into diamond when taken off, dropped, or when they log out.

COMMANDS
  /strength | /withdraw [amount] | /strengthitem [player] [amount] (admin)
  /strength give <player> [amount] (admin) | /strength set <player> <1-5> (admin)

BUILD (needs JDK 25)
  gradle build      -> build/libs/StrengthSMP.jar
Put the jar in the server's plugins folder and restart.

NO JDK? BUILD ON GITHUB (free, no installs)
  1. Make a new GitHub repository and upload everything in this folder (including the .github folder).
  2. Open the Actions tab -> "Build StrengthSMP" -> let it finish (or press Run workflow).
  3. Open the finished run and download the "StrengthSMP" artifact. It is a .zip with StrengthSMP.jar inside.
