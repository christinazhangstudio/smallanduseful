import { json } from '@sveltejs/kit';
import { getSession, publishSession, readSessionJSON, sessionId } from '$lib/server/translation-sessions';
import type { RequestEvent } from './$types';

export const prerender = false;

export async function GET(event: RequestEvent) {
	return json(await getSession(sessionId(event)));
}

export async function PUT(event: RequestEvent) {
	const id = sessionId(event);
	const value = await readSessionJSON(event, 131072);
	return json({ session: await publishSession(id, value) });
}
