package shark.dive.agent

import shark.dive.ObjectListFilter
import shark.dive.Place
import shark.dive.exactHexObjectId

/**
 * How a place of a heap dump is spelled to an agent, and read back off one.
 *
 * `shark.dive.Place` is where a tab is, and this is its whole vocabulary as a single string an agent can
 * be answered with and then hand back: `0x12d368b8`, `leaks`, `objects:android.graphics.Bitmap`. Both
 * directions in one file because they are one spelling — a place written one way and read another is a place
 * an agent can be told about and then cannot go to.
 *
 * Deliberately not `shark://` links, which name a place too: a link names the *heap dump* as well, and often
 * the window, so it is the thing to hand to a person — who has neither in front of them — rather than the
 * thing to pass back over a protocol where both are already known. See [AgentTools] `show`.
 */
internal fun AgentArguments.place(): Place {
  val text = string(PLACE)
  return when {
    text.startsWith(HEX_PREFIX) -> Place.Object(objectIdOf(PLACE, text))
    text == PLACE_LEAKS -> Place.Leaks()
    text == PLACE_OBJECTS -> Place.Objects()
    text == PLACE_STARRED -> Place.Starred
    text == PLACE_AGENT_LOGS -> Place.AgentLogs
    text.startsWith("$PLACE_AGENT_LOGS$PLACE_SEPARATOR") ->
      Place.AgentLog(text.substringAfter(PLACE_SEPARATOR))
    text.startsWith("$PLACE_OBJECTS$PLACE_SEPARATOR") -> Place.Objects(
      ObjectListFilter(query = text.substringAfter(PLACE_SEPARATOR))
    )
    else -> throw AgentRefusal("\"$text\" is no place of a heap dump. $PLACES_ARE")
  }
}

/**
 * The same place written back, and null for one an agent has no way of naming.
 *
 * One of the two can't be: the pile of objects a rectangle had no room for, because which objects are in it
 * follows from how wide the window is, so there is nothing here that would name it again on a screen of
 * another size.
 *
 * The other, a page of the reference, has no name here on purpose. It is what a *person* reads to find out
 * what a label on screen means; the method is what an agent is handed for the same thing, twice, and
 * sending it to read the human's copy would be the same prose a third time. See `AgentMethod`.
 */
internal fun placeText(place: Place): String? = when (place) {
  is Place.Object -> exactHexObjectId(place.objectId)
  is Place.Objects ->
    if (place.filter.query.isEmpty()) {
      PLACE_OBJECTS
    } else {
      "$PLACE_OBJECTS$PLACE_SEPARATOR${place.filter.query}"
    }
  is Place.Leaks -> PLACE_LEAKS
  is Place.Starred -> PLACE_STARRED
  is Place.AgentLogs -> PLACE_AGENT_LOGS
  is Place.AgentLog -> "$PLACE_AGENT_LOGS$PLACE_SEPARATOR${place.sessionId}"
  is Place.SmallerObjects -> null
  is Place.Reference -> null
}

/** What every tool taking one says, so that a place is described the one way. */
internal fun place() = string("Which place of the heap dump. $PLACES_ARE")

internal const val PLACE = "place"

private const val PLACE_LEAKS = "leaks"
private const val PLACE_OBJECTS = "objects"
private const val PLACE_STARRED = "starred"
private const val PLACE_AGENT_LOGS = "agent-logs"

/** Between a screen and which of it, since three of these take one. */
private const val PLACE_SEPARATOR = ":"

/** Every place there is, said the one way, since a schema and a refusal both have to list them. */
private const val PLACES_ARE =
  "A place is an object's `0x…` address, \"$PLACE_LEAKS\", \"$PLACE_OBJECTS\", " +
    "\"$PLACE_OBJECTS$PLACE_SEPARATOR<class name>\", \"$PLACE_STARRED\", \"$PLACE_AGENT_LOGS\" or " +
    "\"$PLACE_AGENT_LOGS$PLACE_SEPARATOR<session id>\"."
