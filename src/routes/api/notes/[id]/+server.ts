import { error, json } from '@sveltejs/kit';
import { withNotes, type Note } from '$lib/server/notes';
import type { RequestEvent } from './$types';

export const prerender = false;

function checkRequest({ request, url, params }: RequestEvent) {
	const origin = request.headers.get('origin');
	if (origin && origin !== url.origin) error(403, 'Cross-origin writes are not allowed.');
	if (!params.id || params.id.length > 200) error(400, 'Invalid note ID.');
	return params.id;
}

export async function PUT(event: RequestEvent) {
	const id = checkRequest(event);
	let value: unknown;
	try {
		value = await event.request.json();
	} catch {
		error(400, 'Expected a JSON note.');
	}
	if (
		!value || typeof value !== 'object' ||
		!('text' in value) || typeof value.text !== 'string' ||
		!value.text.trim() || value.text.length > 100_000 ||
		!('color' in value) || typeof value.color !== 'number' ||
		!Number.isSafeInteger(value.color) || value.color < 0
	) error(400, 'A note needs text (up to 100,000 characters) and a nonnegative integer color.');
	const note: Note = { id, text: value.text.trim(), color: value.color };
	await withNotes((collection) => collection.updateOne({ id }, { $set: note }, { upsert: true }));
	return json(note, { headers: { 'cache-control': 'no-store' } });
}

export async function DELETE(event: RequestEvent) {
	const id = checkRequest(event);
	await withNotes((collection) => collection.deleteOne({ id }));
	return new Response(null, { status: 204 });
}
