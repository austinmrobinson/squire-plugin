# Squire

Squire is your OSRS companion: an AI in your RuneLite sidebar that knows your account, ready to help you gear up and prepare like a good squire should. Ask what to do next, how to gear for a boss, what a DPS upgrade is worth, or where to get an item, and get answers based on your real levels, quests, diaries, bank and gear.

## Features

- **Chat that knows your account**: progression advice for mains, ironmen and hardcores, checked against your actual requirements
- **Gear and DPS**: compares realistic upgrades for your account using a DPS calculator
- **Boss setups**: suggests inventory setups and exports them to Inventory Setups, Bank Tags and Ground Markers
- **Sources**: answers cite the OSRS Wiki, the official news and other pages it read
- **In-game chat**: type `::squire <question>` or press Ctrl+B to ask from the chatbox
- **Progress**: time played, recent XP and activity at a glance
- **Chat history**: several chats, each can run at the same time
- **Use it in other AI apps**: connect Claude, ChatGPT, Cursor or any MCP client to your account from Settings, Connect an AI app. Sign-in uses a one-time code from the plugin; disconnect any time

## Data and privacy

Nothing is sent until you press **Turn on Squire**. After that the plugin sends the following to the Squire server:

- your character name and progress (levels, quests, diaries, combat achievements, collection log, kill counts)
- your bank, inventory, equipment and notable loot
- what you spend your time on, your world and location
- your messages to Squire, which are answered by an AI model
- your IP address, which any server sees. Squire only keeps a one-way hash of it to limit sign-ups

It never sends your password, other players' information or your chat with other players. You can delete everything from **Settings → Delete my data**. The full privacy notice is at the server's `/privacy` page.

Squire gives advice; it does not play the game for you, type into your chatbox or give live boss mechanics.

## Free tier and your own key

Everyone gets a number of free messages a day. For unlimited use, add your own [Vercel AI Gateway](https://vercel.com/ai-gateway) key in the plugin settings. It's sent once, stored encrypted on the server and cleared from your RuneLite profile.

## Server

The server (Next.js, Postgres, the agent and its tools) is open source at https://github.com/austinmrobinson/rs-buddy.
