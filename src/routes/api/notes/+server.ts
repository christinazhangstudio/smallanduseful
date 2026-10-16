import { json } from '@sveltejs/kit';
import { withNotes } from '$lib/server/notes';

export const prerender = false;

export async function GET() {
	const notes = await withNotes((collection) =>
		collection.find({}, { projection: { _id: 0 } }).sort({ _id: -1 }).toArray()
	);
	return json(notes, { headers: { 'cache-control': 'no-store' } });
}
