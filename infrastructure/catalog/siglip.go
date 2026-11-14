package catalog

// Resemblance thresholds for SigLIP 2 (base, 224 pixels). Its similarities
// between a description and a picture are small numbers, around 0.1 for a
// match: the defaults of the stand-in encoder do not suit it.
//
// These values are provisional, read off a handful of test pictures. They are
// replaced by the measure on the public benchmark: see docs/VISION.md.
const (
	SigLIP2Margin = 0.03
	SigLIP2Floor  = 0.07
)
