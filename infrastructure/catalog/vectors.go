package catalog

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
)

// Vectors are kept in memory and compared one by one. At the size of a
// personal library (tens of thousands of vectors) a full pass takes a few
// milliseconds and misses nothing, which an approximate index cannot promise.

const vectorsFile = "vectors.bin"

var vectorsMagic = [8]byte{'c', 'a', 't', 'v', 'e', 'c', '0', '1'}

// normalized returns a copy of v with length 1, so that a dot product is a
// cosine. A zero vector is returned as nil: it describes nothing.
func normalized(v []float32) []float32 {
	var sum float64
	for _, x := range v {
		sum += float64(x) * float64(x)
	}
	if sum == 0 || math.IsNaN(sum) || math.IsInf(sum, 0) {
		return nil
	}
	norm := float32(math.Sqrt(sum))
	out := make([]float32, len(v))
	for i, x := range v {
		out[i] = x / norm
	}
	return out
}

func dot(a, b []float32) float32 {
	if len(a) != len(b) {
		return 0
	}
	var sum float32
	for i := range a {
		sum += a[i] * b[i]
	}
	return sum
}

// best returns the highest similarity between q and any of the vectors: a
// video is as close to a description as its closest frame.
func best(q []float32, vectors [][]float32) (float32, bool) {
	found := false
	var top float32
	for _, v := range vectors {
		if s := dot(q, v); !found || s > top {
			top, found = s, true
		}
	}
	return top, found
}

func saveVectors(dir string, vectors map[string][][]float32) error {
	tmp, err := os.CreateTemp(dir, vectorsFile+".*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	w := bufio.NewWriter(tmp)
	write := func(v any) {
		if err == nil {
			err = binary.Write(w, binary.LittleEndian, v)
		}
	}
	write(vectorsMagic)
	write(uint32(len(vectors)))
	for id, list := range vectors {
		write(uint32(len(id)))
		if err == nil {
			_, err = w.WriteString(id)
		}
		write(uint32(len(list)))
		for _, v := range list {
			write(uint32(len(v)))
			write(v)
		}
	}
	if err == nil {
		err = w.Flush()
	}
	if err == nil {
		err = tmp.Sync()
	}
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return fmt.Errorf("catalog: writing vectors: %w", err)
	}
	// The file is replaced in one step: a crash leaves the old one or the new
	// one, never half of each.
	return os.Rename(tmp.Name(), filepath.Join(dir, vectorsFile))
}

// maxVectorDim and friends bound what a damaged file can make us allocate.
const (
	maxVectorDim   = 8192
	maxVectorCount = 100_000
	maxIDLength    = 4096
)

func loadVectors(dir string) (map[string][][]float32, error) {
	file, err := os.Open(filepath.Join(dir, vectorsFile))
	if errors.Is(err, os.ErrNotExist) {
		return map[string][][]float32{}, nil
	}
	if err != nil {
		return nil, err
	}
	defer file.Close()
	r := bufio.NewReader(file)
	read := func(v any) {
		if err == nil {
			err = binary.Read(r, binary.LittleEndian, v)
		}
	}
	var magic [8]byte
	read(&magic)
	if err == nil && magic != vectorsMagic {
		return nil, fmt.Errorf("catalog: %s is not a vectors file", vectorsFile)
	}
	var count uint32
	read(&count)
	out := make(map[string][][]float32)
	for i := uint32(0); i < count && err == nil; i++ {
		var idLen, n uint32
		read(&idLen)
		if err == nil && idLen > maxIDLength {
			err = fmt.Errorf("identifier of %d bytes", idLen)
		}
		id := make([]byte, idLen)
		if err == nil {
			_, err = io.ReadFull(r, id)
		}
		read(&n)
		if err == nil && n > maxVectorCount {
			err = fmt.Errorf("%d vectors for one file", n)
		}
		var list [][]float32
		for k := uint32(0); k < n && err == nil; k++ {
			var dim uint32
			read(&dim)
			if err == nil && dim > maxVectorDim {
				err = fmt.Errorf("vector of %d dimensions", dim)
			}
			v := make([]float32, dim)
			read(v)
			list = append(list, v)
		}
		if err == nil {
			out[string(id)] = list
		}
	}
	if err != nil {
		return nil, fmt.Errorf("catalog: reading vectors: %w", err)
	}
	return out, nil
}
