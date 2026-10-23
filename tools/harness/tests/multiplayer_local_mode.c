/* Multiplayer's Create Game items: which mode each names, and that only
LOCAL (BOTS) makes the server take no remote players (menu_functions.c's
multiplayer_mode_set and multiplayer_host), which is what lets bots join. */
#include "harness.h"

enum
{
	_multiplayer_mode_server_browser,
	_multiplayer_mode_lan,
	_multiplayer_mode_direct_link,
	_multiplayer_mode_host_internet,
	_multiplayer_mode_host_lan,
	_multiplayer_mode_host_local,
};

struct widget_instance { char const *name; };
struct event_record;

static struct
{
	short mode;
	boolean game_private, cooperative_maximum_players_set;
} multiplayer;

/* what the server made sets (network_game_start_new_server: it accepts
remote players until multiplayer_host says otherwise) */
static int accept_remote;
static int hosting_allowed, hosting_public;
static boolean host_result = TRUE;

void network_game_accept_remote_connections(boolean accept) { accept_remote = accept; }
void p2p_set_hosting_allowed(int allowed) { hosting_allowed = allowed; }
void p2p_set_hosting_public(int public) { hosting_public = public; }
int config_boolean(char const *name) { (void)name; return 0; }
boolean ui_widget_port_host(struct widget_instance *widget, struct event_record *event, boolean *widget_deleted)
{
	(void)widget;
	(void)event;
	(void)widget_deleted;
	accept_remote = TRUE;
	return host_result;
}
static boolean multiplayer_player(short controller) { (void)controller; return TRUE; }

#include "under_test.inc"

/* the names the game gives the items' instances (cut to 31 characters) */
static char const *const create_names[] = {
	"multiplayer_type_create_interne",
	"multiplayer_type_create_lan_item",
	"multiplayer_type_create_local_",
};
static short const create_modes[] = {
	_multiplayer_mode_host_internet,
	_multiplayer_mode_host_lan,
	_multiplayer_mode_host_local,
};

int main(int argc, char **argv)
{
	const char *case_name = argc > 1 ? argv[1] : "";

	CASE("modes") {
		static char const *const names[] = {
			"multiplayer_type_join_internet_item", "multiplayer_type_join_lan_item",
			"multiplayer_type_join_direct_item", "multiplayer_type_create_interne",
			"multiplayer_type_create_lan_item", "multiplayer_type_create_local_",
		};
		static short const modes[] = {
			_multiplayer_mode_server_browser, _multiplayer_mode_lan, _multiplayer_mode_direct_link,
			_multiplayer_mode_host_internet, _multiplayer_mode_host_lan, _multiplayer_mode_host_local,
		};
		for (int index = 0; index < 6; index++) {
			struct widget_instance widget = { names[index] };
			multiplayer.mode = NONE;
			multiplayer_mode_set(&widget);
			CHECK(multiplayer.mode == modes[index], "%s", names[index]);
		}
	}

	CASE("hosts") {
		for (int index = 0; index < 3; index++) {
			struct widget_instance widget = { create_names[index] };
			multiplayer.mode = NONE;
			accept_remote = NONE;
			hosting_allowed = hosting_public = NONE;
			CHECK(multiplayer_host(&widget, NULL, 0, NULL), "host failed");
			CHECK(multiplayer.mode == create_modes[index], "%s", create_names[index]);
			/* (only a local game refuses the machines that are not here) */
			CHECK(accept_remote == (create_modes[index] != _multiplayer_mode_host_local), "%s", create_names[index]);
			CHECK(hosting_allowed == (create_modes[index] == _multiplayer_mode_host_internet), "%s", create_names[index]);
			CHECK(hosting_public == FALSE, "%s", create_names[index]);
		}
		/* (a server that could not be made keeps the flag as it was) */
		{
			struct widget_instance widget = { "multiplayer_type_create_local_" };
			host_result = FALSE;
			accept_remote = TRUE;
			CHECK(!multiplayer_host(&widget, NULL, 0, NULL), "host failed");
			CHECK(accept_remote == TRUE, "a failed host changed the flag");
			host_result = TRUE;
		}
	}

	return 0;
}
