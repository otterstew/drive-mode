# Server fixes for drive mode (12 Nov 2026)

**Deployed 12 Nov 2026 as drive-mode version 22** (straight to Supabase, on top of
version 21). They still need copying into `supabase/functions/drive-mode/index.ts` in
the open-brain repo: this session couldn't reach that repo, and the next
`supabase functions deploy drive-mode` from the repo would otherwise undo them.
Delete this file once they're in.

What the 7 Nov logs showed:

1. "Jarvis, open Google Maps and give me directions to" (cut off before the place).
   The first reply was right: "Where would you like to go?". But the double-check
   for "said it did something without doing it" matched "nothing is playing", asked
   the AI again, got an empty answer, and replaced the reply with "Sorry, I lost my
   train of thought". That second call also made the turn take 7.6 s instead of about 2 s.
2. His answer, "Open Google Maps and give me directions to Synrons", was thrown
   away because it didn't start with "Jarvis". So were "Play me any song you trust
   on Spotify" and the like.

## 1. Add these next to `saysName`

```ts
// It just asked him something: his answer needn't start with the name
// (7 Nov: "Where would you like to go?", then his answer was ignored).
function askedHim(history: unknown) {
  if (!Array.isArray(history)) return false;
  const last = history[history.length - 1];
  return last?.role === "assistant" && String(last?.text ?? "").includes("?");
}
// A plain command at the very start counts as meant for it, name or not
// (7 Nov: "Open Google Maps and give me directions to Synrons" was ignored).
const COMMAND_START = /^\s*(?:(?:ok(?:ay)?|right|um|uh|er|so|can you|could you|please)[,\s]+)*(?:play|put on|pause|resume|skip|next song|open (?:google )?maps|navigate|take me|directions|give me directions|drive to)\b/i;
```

## 2. In `turn()`, the name check becomes

```ts
    if (body.handsFree && body.wakeName && !saysName(heard, body.wakeName) &&
        !askedHim(body.history) && !COMMAND_START.test(heard)) {
```

## 3. In `turn()`, the retry becomes

```ts
  // It said it did something but sent nothing: ask again, told plainly.
  // (Seen 6 Nov: "This is Here Comes the Sun", and no command sent.)
  // Not when it asked him something (7 Nov: "Where would you like to go?
  // ...nothing is playing yet" was retried into silence), and the first
  // answer is kept if the retry says nothing.
  if (!actions.length && !end && !say.includes("?") && CLAIMS_ACTION.test(say) && (ASKS_FOR_ACTION.test(lastWords) || ABOUT_ACTIONS.test(say))) {
    console.log("drive-retry", JSON.stringify({ lastWords, say }));
    const again = await converse(system, history, TOOLS, runTool,
      `Note from the app: your last draft said "${say.slice(0, 160)}" but no tool was called, so nothing happened. Call the tool now if he wants music or directions.`);
    if (again.trim() || actions.length) say = again;
  }
```
