package catalog

// Resemblance thresholds for SigLIP 2 (base, 224 pixels). Its similarities
// between a description and a picture are small numbers, around 0.13 for a
// match: the defaults of the stand-in encoder do not suit it.
//
// They were measured on XM3600, 3 600 photographs with 8 562 descriptions
// written in French by people (see docs/VISION.md):
//
//   - 95 % of the descriptions are at 0.084 or more from their own photograph:
//     below the floor, a picture is very unlikely to show what was described;
//   - for 95 % of the descriptions, their own photograph is within 0.036 of
//     the closest one: within the margin, a picture may well be the one.
const (
	SigLIP2Margin = 0.04
	SigLIP2Floor  = 0.08
)
