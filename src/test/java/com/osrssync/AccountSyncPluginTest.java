package com.osrssync;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AccountSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AccountSyncPlugin.class);
		RuneLite.main(args);
	}
}
